# Write-up: Seat Reservation at Scale

A JSON HTTP service that sells assigned seats and stays correct under load: a seat is never sold twice, a user never exceeds the per-show limit, and a retried request never books twice. This document covers the design decisions and where each deliverable lives.

## Deliverables

| # | Deliverable | Where |
|---|---|---|
| 1 | Public Git repo with full commit history | https://github.com/Jigar-Joisar/seat-reservation (`main`, see [commit history](#1-commit-history)) |
| 2 | Live URL | https://seat-reservation-9zhl.onrender.com (Swagger UI at `/`, health at `/health/ready`) |
| 3 | One-command burst script | `./burst.sh <BASE_URL>` or `make burst URL=<BASE_URL>`; usage in the [README](README.md#burst-tool-load--adversarial-correctness-suite) |
| 4 | Metrics and logs access | Metrics: `/actuator/prometheus`. Logs: `/ops/logs` (public, scrubbed) and structured JSON on stdout. See [Observability](#observability-and-what-pages-me-at-2am) and the [README](README.md#public-log-access) |
| 5 | This write-up | below |

Evidence that the deployed service behaves correctly is in [docs/LIVE-TEST-REPORT.md](docs/LIVE-TEST-REPORT.md) with the raw burst output, JSON report and Prometheus snapshot in `docs/evidence/`.

### 1. Commit history
The history shows the real order of work: a baseline scaffold, then a rewrite of the core, then features and hardening each added with their tests (timed holds, run script, Swagger UI, validation and concurrency test suites, the adversarial burst suite, crash-durability fix, Postgres support, per-scenario timing, the log endpoint, the live report). Two honest caveats: the baseline commit was made shortly before the work began, and the core rewrite (JDBC, conditional UPDATE, allocation lock, scoped idempotency, JWT auth, readiness, metrics) landed as one large commit rather than one per feature, because those pieces were built and tested together.

## The atomic decision

**Mechanism.** Each seat is claimed by a single conditional statement inside a `READ_COMMITTED` transaction:

```sql
UPDATE seats SET status = ?, user_id = ?, reservation_id = ?
WHERE show_id = ? AND seat_number = ? AND status = 'available'
```

A row count of 1 means this request won the seat; 0 means it was already taken and the request returns a clean `409 seat_taken`. There is no read-then-write step.

**Why it is race-free.** An `UPDATE` takes a row lock. When 500 requests target the same seat, the database serializes them on that row. The first to commit flips `status`; every later `UPDATE` waits for the lock, then re-evaluates the `WHERE` clause against the committed row (this is how both PostgreSQL and H2 behave under `READ_COMMITTED`) and matches nothing. So at most one request can ever claim a seat, regardless of how many application threads or instances are running, because the guarantee lives in the database and not in application memory or locks. We deliberately avoid `SERIALIZABLE`, which on H2 caused lock storms.

**Evidence.** `ReservationApiTest.hotSeatStormExactlyOneWinner` (500 users, one seat: exactly one `201`, 499 `409`, no 5xx), the burst's scenario 1 locally and on the live service (`1 x 201, 499 x 409`), and a full external audit after the larger scenarios that fetches every reservation and checks that no seat appears in two live reservations. We also checked that the suite can fail: removing the `AND status = 'available'` guard in a throwaway copy makes it fail on "exactly one 201" and "no seat in two live reservations".

**Multi-seat requests and deadlock avoidance.** Seats in a request are de-duplicated, sorted by name, and claimed in that order inside one transaction. Requests for `[A1, A2]` and `[A2, A1]` therefore both attempt `A1` first, so a lock cycle cannot form. If any seat's `UPDATE` matches zero rows, the whole transaction rolls back, so multi-seat requests are all-or-nothing and a failed request leaves nothing behind. Every other write path (confirm, cancel, expiry) uses the same lock order: allocation row, then reservation row, then seats in sorted order. Any database lock timeout or deadlock victim is mapped to a retryable `409 contention`, never a 5xx. Scenario 5 of the burst (300 users, random 2-4 seats in random order over 12 seats) produced only `201`/`409`.

**Per-user limit.** Each (show, user) has a row in `user_show_allocations` holding `seats_held`. It is locked with `SELECT ... FOR UPDATE` at the start of every reserve, hold, confirm, cancel and expiry for that user and show, so all of one user's operations on a show are serialized. The check `seats_held + requested > limit` is therefore race-free, and it counts seats, not reservations. Test: ten parallel single-seat requests with a limit of 4 yield exactly 4 wins; the burst also sends eight parallel requests from each of 150 users.

## Idempotency

**Where the key is stored.** In the `reservations` table, with `UNIQUE (user_id, idempotency_key)`. Keys are scoped per user, so one user's key can never return another user's reservation. The key is accepted as `idempotency_key` in the body or an `Idempotency-Key` header.

**How a request is processed exactly once.** The lookup is repeated after taking the user's allocation lock, so concurrent duplicates serialize: one creates the reservation, the others find it and replay it. The unique constraint is the backstop: if a duplicate insert ever slips through, that transaction rolls back and the winner's reservation is returned. A first success returns `201`; a replay returns `200` with the original reservation, the header `Idempotent-Replay: true`, and moves nothing. Returning `200` rather than `201` keeps "exactly one 201 per seat" true. Declined requests do not consume the key, so a client can retry after a `409`.

**Same key, different body.** Seat lists are compared as sorted sets and the show id is compared too. The same key with different seats or a different show returns `409 idempotency_conflict` instead of silently returning a reservation for something else. (An earlier version let a key reused on another show return the first show's reservation; fixed with a regression test.) The burst covers 50 parallel same-key requests, 150 users sending three identical parallel requests, and same key with different seats.

## Holds and expiry

Two booking styles share the same atomic claim. `reserve` confirms immediately. `hold` claims the seats as `held` with an `expires_at` timestamp (`HOLD_TTL_SECONDS`, default 300 s); the seats are unavailable to others and count against the owner's limit.

* **Confirm** (owner only): lock the allocation row, lock the reservation row, require `status = 'held'` and `expires_at` in the future, then `UPDATE seats SET status = 'confirmed' WHERE reservation_id = ? AND status = 'held'`. Confirming an already confirmed reservation is idempotent; otherwise `409 hold_expired`.
* **Expiry sweeper** (every `HOLD_SWEEP_MS`, default 5 s): for each overdue hold, in its own transaction, lock allocation then reservation, re-check it is still `held` and overdue, release only seats where `reservation_id = ? AND status = 'held'`, mark the reservation `expired`, and decrement `seats_held`.
* **Cancel** (owner only, idempotent): releases `held` or `confirmed` seats keyed on this reservation's id.
* **Why a seat can never be resurrected or double-assigned.** Every release is keyed on the reservation id and the expected status, so a stale cancel or expiry cannot free a seat that now belongs to someone else (tested with a stale cancel after a re-book). Confirm racing expiry or cancel is serialized by the reservation row lock; whichever commits first wins and the loser's recheck is a no-op. A confirm that returned `200` is never undone by a later sweep.
* **Sweeper latency does not affect correctness.** An overdue hold that has not been swept yet is rejected by the timestamp check at confirm time. Only the moment the seat becomes re-bookable depends on the sweep interval. After a crash or restart, overdue holds are released on the first sweep.

## Consistency versus availability

The database is the single source of truth, and the service chooses **consistency over availability (CP)**. If the database cannot be reached, requests fail and `/health/ready` returns `503` (checked on a separate 2-connection pool so a saturated request pool cannot flap readiness); the service never answers from a cache or accepts a booking it cannot record.

* **Tested in-process:** `ReadinessFailClosedTest` closes the readiness pool and asserts `/health/ready` is `503` while `/health/live` stays `200`; it also closes the main pool and asserts reservations and show reads return `503 service_unavailable` with `Retry-After` while token issuing still works.
* **Tested live:** the Render Postgres was suspended during a probe that records live, ready and a reservation attempt every second ([report](docs/LIVE-TEST-REPORT.md#database-outage-test)). The application went to readiness `503` and reservation `503` immediately, nothing was booked during the outage, and the post-outage audit passed (every `201` is a confirmed seat, no double booking). It recovered by itself when the database returned.
* **Finding:** Render's health check is pointed at `/health/ready`, so about 16 s into the outage the platform took the instance out of rotation and clients saw Render's `502` for about 3.5 minutes instead of the application's `503` + `Retry-After`. The data stayed safe, but the graceful path was mostly hidden. Pointing the platform check at `/health/live` (keeping `/health/ready` for monitoring) would expose it; that change is recommended and not yet applied.
* **Not tested:** a partition that drops the database while a heavy burst is in flight, and the exact time to recover after the database resumed (the resume time was not recorded).
* **Durability.** PostgreSQL flushes each commit to its write-ahead log before the `201` is sent. On local H2 the same guarantee needed a fix: a `kill -9` test showed the default 500 ms write delay could lose the most recent confirmed bookings, so the default URL sets `WRITE_DELAY=0` (about 20 % lower throughput on a laptop). Data, idempotency keys, and expired-hold cleanup were verified to recover after a hard crash.
* **Scaling.** The no-double-sell guarantee does not depend on the number of application instances. We still run one, because the Prometheus counters are per process and would split across instances.

## Observability and what pages me at 2am

* **Metrics** (`/actuator/prometheus`): `reservations_confirmed_total`, `reservations_held_total`, `reservations_cancelled_total`, `reservations_expired_total`, `reservations_declined_total{reason}` (`seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_conflict`, `hold_expired`, `invalid_seat`, `contention`), `reservation_duration_seconds`, gauges `seats_available|held|confirmed{show_id}`, plus HTTP and Hikari pool metrics. They reconcile with the API and with what clients observe: in the live burst every counter equalled the client-side count exactly (see the live report).
* **Logs:** one JSON line per request and one per state change, all carrying a `request_id` that is echoed in the `X-Request-ID` response header. Render has no public log URL, so `GET /ops/logs` serves the last 2000 events. It is safe to expose: an allow-list of fields, only the access log and this application's loggers as sources, pattern scrubbing of JWT, bearer and connection-URL strings, and a test that a planted JWT, the admin secret and the Authorization header never come out.

I would be paged for:

1. **Any 5xx** (`http_server_requests_seconds_count{status=~"5.."}` increasing). Declines are 4xx by design, so a 5xx is a bug or an outage.
2. **Readiness failing or the service restarting** (`/health/ready` not 200, container restarts).
3. **Contention or pool saturation:** `reservations_declined_total{reason="contention"}` rising, `hikaricp_connections_pending` above zero for a sustained period, `hikaricp_connections_timeout_total` increasing.
4. **Latency:** p99 of `reservation_duration_seconds` far above its baseline during a sale.
5. **Invariant drift:** `seats_available + seats_held + seats_confirmed` differing from a show's total. This should never move, so it pages immediately.
6. **Business sanity:** zero confirmations during a sale window, or `confirmed_total - cancelled_total` diverging from the sum of confirmed seats.

## AI usage

I used an AI coding agent (Devin) heavily, working interactively in my terminal. This section separates what I directed from what the agent decided, and what the evidence actually covers.

**What I directed** (my instructions and choices):
* Reviewing the existing scaffold against the challenge text, and listing every requirement before any implementation, then planning and testing features one at a time.
* Supporting both immediate reservation and timed holds with confirmation, with immediate reserve returning `confirmed`.
* Admin-only show creation using a secret, and token-issuing endpoints for users and admins.
* A configurable `run.sh`, incremental local commits, and publishing the repository.
* Asking for an easy way to test the API by hand (the agent proposed static Swagger UI over a custom UI; I accepted).
* Testing cold restarts and hard crashes, running the burst against the live service, switching the deployment to Render's PostgreSQL, a public log endpoint, per-scenario timing in the burst output, and an explicit instruction to check the live burst before making further changes.
* Deployment choices: Render, free tier, and rotating the secrets after the live tests.

**What the agent decided and did:**
* Found the defects in the scaffold: camelCase JSON binding that turned valid snake_case requests into 500s, cancel not freeing seats, a per-user limit that counted reservations and was racy, no usable token issuance, readiness that always returned 200, and a globally scoped idempotency key.
* Chose the design: a rewrite from JPA to plain JDBC, the conditional-`UPDATE` claim, the per-user allocation lock, the lock ordering, and `READ_COMMITTED` instead of `SERIALIZABLE`. Wrote most of the code, the 50 tests, the burst suite, the OpenAPI spec, and the documentation.

**Where the agent was wrong or sloppy, and how it showed up:**
* Produced misleading results in its own testing: it left old server processes running against a rebuilt jar and deleted a data directory under a live process, which caused spurious 500s and a damaged local database; it then documented the rule in `AGENTS.md`.
* A first `kill -9` test lost data, which exposed the H2 write-delay problem (fixed with `WRITE_DELAY=0`).
* Two bursts started at once overloaded the free instance and produced 502s; those results were discarded and the harness is run as a single process.
* A registration file for the database-URL converter was silently ignored inside the packaged jar; it was found by running the packaged jar with a bad URL and replaced with an initializer registered in `main()`.
* One burst expectation was wrong: Render's edge returns its own `403` for SQL-injection-looking strings before the app sees them.
* Two of its predictions were wrong and were corrected after measurement: it said the full-size run would take 11-15 minutes (it is about 35, because the suite makes about 44,000 calls, not 22,000), and it said readiness failing would leave the app serving `503` for ordinary requests (behind Render's health check the platform removed the instance instead).
* An idempotency gap (the same key reused on a different show returned the first show's reservation) survived the first design and was found while the burst suite was being hardened; it is now fixed with a regression test.

**What is verified by running, and what is not:**
* Verified: 51 automated tests, two full local burst runs (default and short hold TTL), a hard-crash restart test, five live burst runs against the Render deployment (about 56,000 calls, no 5xx from the application, every counter reconciled), and a live database-outage probe with a passing audit (see the live report).
* Reported, not hidden: the first live scale-0.5 run had one request that received no response (transport failure, cause undetermined; the clean second run and an isolated rerun did not reproduce it).
* Not verified: the full-size burst (scale 1.0, about 44,000 calls, projected at about 35 minutes) against the free instance, a confirm winning the expiry race on the live service, a partition under heavy load, and building the Docker image locally (Docker was not available; Render builds the image on every deploy).

## Next

1. **Make the outage graceful on the platform:** point Render's health check at `/health/live`, lower Hikari's 60 s connection timeout so requests fail fast during an outage, and re-run the outage probe and the burst (the `503` + `Retry-After` mapping itself already exists and is tested).
2. **Postgres operations:** set `lock_timeout` and `statement_timeout` on connections so one stuck lock cannot hold a pooled connection; use a paid, highly available database (the free one expires after 30 days).
3. **Scale out safely:** move counters to a shared store or scrape per instance and aggregate, then run several application instances against one database.
4. **Real identity:** replace the demo token issuer with an identity provider and short-lived tokens.
5. **Abuse protection:** rate limiting per user and per IP, and a cap on the number of per-show gauges.
6. **Observability:** distributed tracing, a Grafana dashboard, and alert rules for the six paging conditions above.
7. **Capacity:** run the full-size burst against a larger instance to measure real headroom; the free tier handles about 25-35 requests/s. Also find the cause of the single unexplained transport failure seen in one scale-0.5 run.
