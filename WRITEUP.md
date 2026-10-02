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
I chose the **explicit cancel** model, not timed holds. Reserve confirms immediately. Cancel is owner-only (403 otherwise), idempotent, and runs `UPDATE seats SET status='available' … WHERE reservation_id=? AND status='confirmed'`; because it is keyed on this reservation's id it can never free or reassign a seat that belongs to someone else (tested: stale cancel after a re-book). The `held` state exists in the schema/invariant but is always 0 today.

## Consistency vs availability
The database is the single source of truth and is CP: if it is unreachable, `/health/ready` returns 503 (checked on a separate 2-connection pool so a saturated request pool doesn't flap readiness) and requests fail rather than being served stale. Limits of the current design: H2 is embedded and single-process, so there is exactly one app instance; horizontal scale needs Postgres (the SQL used is portable: conditional UPDATE, `FOR UPDATE`, unique constraints). I did not test the "DB down" path end to end (embedded H2 can't be taken down independently), so the fail-closed behaviour is by construction, not by test.

## Observability / what pages me at 2am
- 5xx rate > 0 (`http_server_requests_seconds_count{status=~"5.."}`): by design declines are 4xx, so any 5xx is a bug or an outage.
- `/health/ready` failing, or restarts.
- `reservations_declined_total{reason="contention"}` rising: lock waits/timeouts, i.e. pool or DB saturation.
- p99 of `reservation_duration_seconds` and Hikari pool pending.
- Invariant drift: `seats_available+held+confirmed` vs total per show (never expected to move).
- Business sanity: `confirmed_total − cancelled_total` should equal sum of confirmed seats; sudden zero confirms during a sale.

## AI usage (honest)
I used an AI coding agent heavily. What it did: reviewed the existing scaffold and found the bugs (camelCase JSON binding causing 500s, cancel not freeing seats, per-user limit counting reservations and racy, no token issuance, fake readiness, globally scoped idempotency key), then wrote most of the code, tests and burst tool. What I directed/decided: the bar to hit (from the brief), keeping H2 for the deadline, reserve returning `confirmed` per the spec sample, owner-cancel instead of timed holds, admin gating via a secret, and a requirement-by-requirement plan implemented and tested feature by feature. I reviewed the SQL and locking order and I can explain them; I have not yet run this beyond one machine, so treat the live-deploy numbers as unverified until the URL is filled in.

## Next
Postgres + multiple instances; timed holds with a sweeper (`HELD -> CONFIRMED|EXPIRED`) using the same conditional-update pattern; real identity provider instead of the demo issuer; rate limiting; DB-down integration test; OpenTelemetry tracing and Grafana dashboard; per-show gauge cardinality cap.
