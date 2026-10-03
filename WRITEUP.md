# Write-up

## The atomic decision
Each seat is claimed with one conditional statement, inside a READ_COMMITTED transaction:

`UPDATE seats SET status='confirmed', user_id=?, reservation_id=? WHERE show_id=? AND seat_number=? AND status='available'`

The row lock plus the `WHERE status='available'` guard means that when 500 buyers race for A12, the database serializes them on that row; the first commit flips it, and every later UPDATE re-evaluates the predicate against the committed row and matches 0 rows. Rowcount 1 = winner, 0 = clean 409 `seat_taken`. There is no read-then-write gap and no application-level lock, so it also holds with multiple app instances against one database.
Verified by `ReservationApiTest.hotSeatStormExactlyOneWinner` (500 users, 1 winner, 499×409, 0×5xx) and by `burst.py`.

**Multi-seat / deadlock.** Seats are de-duplicated and sorted, and updated in that order inside one transaction. Two requests `[A1,A2]` and `[A2,A1]` therefore both touch A1 first; no cycle is possible. If any seat's UPDATE hits 0 rows the transaction rolls back (all-or-nothing). Lock/deadlock timeouts from the DB are mapped to a 409 `contention` (retryable), never a 500.

**Per-user limit.** A `user_show_allocations(show_id,user_id,seats_held)` row is locked `SELECT … FOR UPDATE` at the start of each reserve/cancel. All of one user's operations on one show serialize on it, so `seats_held + n > limit` is a race-free check (counts seats, not reservations). Test: 10 parallel single-seat reserves on limit 4 -> exactly 4 wins.

## Idempotency
Key lives in `reservations` with `UNIQUE(user_id, idempotency_key)` (scoped per user so one user's key can never return another user's reservation). The lookup is done again after taking the allocation lock, so concurrent duplicates cannot both proceed: one creates, the rest see the row and replay it (HTTP 200, `Idempotent-Replay: true`, same `reservation_id`, nothing extra moved). Bodies are compared as sorted seat sets; same key with different seats -> 409 `idempotency_conflict`. The unique constraint is the backstop: on a duplicate-key error we roll back and return the winner. A replay returns 200 rather than 201 so "exactly one 201 per seat" stays true.

## Holds and expiry
Two ways to book. `reserve` confirms immediately. `hold` takes the same atomic conditional UPDATE but sets `status='held'` with a reservation `expires_at` (TTL, default 300s); the seat is unavailable to everyone else and counts toward the owner's limit. Then:
- **confirm** (owner only): lock allocation row, lock reservation row, require `status='held'` and not past `expires_at`, `UPDATE seats SET status='confirmed' WHERE reservation_id=? AND status='held'`. Idempotent if already confirmed; otherwise 409 `hold_expired`.
- **expiry sweeper** (every 5s): for each overdue held reservation, in its own transaction, lock allocation -> reservation, re-check it is still `held` and overdue, release only seats with `reservation_id=? AND status='held'`, mark `expired`, decrement `seats_held`. Racing with confirm/cancel is serialized by the reservation row lock; whoever commits first wins and the loser's conditional update/recheck is a no-op, so a confirmed seat can never be expired and an expiry can never resurrect a confirmed seat.
- **cancel** (owner only, idempotent): releases `held` or `confirmed` seats keyed on this reservation's id, so it can never free or reassign a seat that now belongs to someone else (tested with a stale cancel after a re-book).
Lock order is always allocation -> reservation -> seats (sorted), so none of these paths can deadlock with each other or with reserve. An expired-but-unswept hold is rejected at confirm time by the timestamp check, so correctness does not depend on sweeper latency; only seat re-availability does.

## Consistency vs availability
The database is the single source of truth and is CP: if it is unreachable, `/health/ready` returns 503 (checked on a separate 2-connection pool so a saturated request pool doesn't flap readiness) and requests fail rather than being served stale. In deployment the database is managed Postgres (Render wires `DATABASE_URL` from its own Postgres service), so the readiness probe is a genuine dependency check and shows/reservations survive restarts and redeploys. The no-double-sell guarantee is entirely in the database (conditional UPDATE + unique constraints + `FOR UPDATE`), so it would hold even with several app instances; we still run one, because the Prometheus counters are per-process and scaling out would split them. `ReadinessFailClosedTest` closes the readiness pool and asserts `/health/ready` returns 503 while `/health/live` stays 200. What is still not tested: a real network partition mid-burst (dropping the DB while load is running).

**Crash durability.** With Postgres, every commit is WAL-flushed before the `201` is sent. The same guarantee needed an explicit fix on local H2: its default 500 ms write delay could lose the most recent confirmed bookings in a hard crash (found by `kill -9` testing), so the default H2 URL sets `WRITE_DELAY=0`. The price on a laptop is roughly 20 % throughput. After a crash and restart the data, idempotency keys, expired-hold cleanup and metrics gauges all recover (see README, "Restarts, crashes and cold starts").

## Observability / what pages me at 2am
**Logs.** Every request produces one JSON access line and every state change a business event, all carrying the `request_id` that is echoed in the `X-Request-ID` response header. The platform has no public log URL, so `GET /ops/logs` serves the last 2000 events from an in-memory ring buffer. It is an allow-list of fields plus pattern scrubbing, fed only by the access log and this application's loggers, and a test proves a planted JWT, the admin secret and the Authorization header never come out. The live evidence is in `docs/LIVE-TEST-REPORT.md`.

- 5xx rate > 0 (`http_server_requests_seconds_count{status=~"5.."}`): by design declines are 4xx, so any 5xx is a bug or an outage.
- `/health/ready` failing, or restarts.
- `reservations_declined_total{reason="contention"}` rising: lock waits/timeouts, i.e. pool or DB saturation.
- p99 of `reservation_duration_seconds` and Hikari pool pending.
- Invariant drift: `seats_available+held+confirmed` vs total per show (never expected to move).
- Business sanity: `confirmed_total − cancelled_total` should equal sum of confirmed seats; sudden zero confirms during a sale.

## AI usage (honest)
I used an AI coding agent heavily. What it did: reviewed the existing scaffold and found the bugs (camelCase JSON binding causing 500s, cancel not freeing seats, per-user limit counting reservations and racy, no token issuance, fake readiness, globally scoped idempotency key), then wrote most of the code, tests and burst tool. What I directed/decided: the bar to hit (from the brief), H2 locally with Postgres for the deployment, reserve returning `confirmed` per the spec sample, both an immediate reserve and an optional timed hold/confirm/expire flow, admin gating via a secret, and a requirement-by-requirement plan implemented and tested feature by feature. I reviewed the SQL and locking order and I can explain them. The burst suite has run against the public Render deployment: every correctness check passed (no double-sell, limits, idempotency, ownership, metrics reconciliation, zero 5xx), with free-tier latency of about p50 4.4 s / p99 23 s — slow but correct.

## Next
Multiple instances once metrics move to a shared store (e.g. Prometheus scraping is fine, but live counters would need aggregation); real identity provider instead of the demo issuer; rate limiting; OpenTelemetry tracing and Grafana dashboard; per-show gauge cardinality cap; a `lock_timeout` on the Postgres connections so a stuck lock can never hold a pooled connection indefinitely.
