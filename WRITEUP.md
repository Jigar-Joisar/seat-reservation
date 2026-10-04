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
* **Tested live (twice):** the Render Postgres was suspended during a probe that records live, ready and a reservation attempt every second ([report](docs/LIVE-TEST-REPORT.md#database-outage-test-run-1-before-the-circuit-breaker-and-health-check-change)). Run 1 (health check on `/health/ready`) exposed the platform problem below. Run 2 (health check on `/health/live`, circuit breaker) showed liveness `200` throughout, 109 reservation `503`s with `Retry-After` and no `502`s, nothing booked during the outage, a passing audit, and recovery about 2 s after the database answered.
* **Finding:** Render's health check was pointed at `/health/ready`, so about 16 s into the outage the platform took the instance out of rotation (its documented rule: 15 s of failed checks stops routing, 60 s restarts) and clients saw Render's `502` for about 3.5 minutes instead of the application's `503` + `Retry-After`. The data stayed safe, but the graceful path was hidden.
* **Change made after the finding:** the platform check now points at `/health/live` (`/health/ready` stays for monitoring), and a circuit breaker (`DbHealthMonitor` + `DbGuardFilter`) answers database requests with an immediate `503` + `Retry-After` while a probe on the dedicated health pool fails three times in a row. Fast failure comes from the breaker, not from lowering Hikari's 60 s connection timeout, because the slow tail of a normal burst legitimately waits 40-50 s for a pooled connection. Covered by `DbGuardTest`.
* **Defect found by the live run 2, fixed, re-verified live in run 4:** the breaker had opened 66 s after the outage began instead of about 8 s, because Spring's default single-thread scheduler was held for 60 s by the hold sweeper (blocked on the dead connection pool), so the health probe could not run. A scheduler pool of 4 plus `SchedulerIsolationTest` fixes it; in the re-run the breaker opened 8 s after the outage began, every `503` answered in under 0.5 s, and the audit passed. One request already inside its transaction when the database vanished returned `500` instead of `503` (commit-time `TransactionSystemException`); that case is now mapped to `503` too.
* **Not tested:** a partition that drops the database
* **Durability.** PostgreSQL flushes each commit to its write-ahead log before the `201` is sent. On local H2 the same guarantee needed a fix: a `kill -9` test showed the default 500 ms write delay could lose the most recent confirmed bookings, so the default URL sets `WRITE_DELAY=0` (about 20 % lower throughput on a laptop). Data, idempotency keys, and expired-hold cleanup were verified to recover after a hard crash.
* **Scaling.** The no-double-sell guarantee does not depend on the number of application instances. We still run one, because the Prometheus counters are per process and would split across instances.

## Seat hints (performance, advisory only)

In the full-size run 97 % of requests were `409 seat_taken`, and each still cost about eight database round trips. A hint cache lets a request for an already-taken seat fail early. The design rule is that the cache can only *reject*, never *grant*: the database remains the only thing that can sell a seat, so an empty, evicted, expired, restarted or failing cache changes performance, never an answer.

* **What is cached:** `(show, seat) -> taken` (bounded to 500,000 entries, 60 s TTL) and the immutable price and per-user limit of a show. A hinted request still runs the idempotency lookup first, so a retry of a winning request still replays with `200`.
* **Staleness:** a stale "taken" hint would wrongly reject a released seat. Releases (`cancel`, hold expiry) bump a global epoch and then remove their seats; a hint is only stored if the epoch is unchanged since the request began, so a hint learned from a read that predates a release is dropped. Invalidation runs in a `finally` after the transaction, because removing a hint is always safe. The TTL bounds anything this misses.
* **Tested:** `SeatHintsTest`, `LocalSeatHintsTest`, `SeatHintsOffTest`, and every existing concurrency test now also asserts that no *available* seat carries a hint. Disabling the invalidation makes four of those tests fail.
* **Behaviour change:** a request that is doomed for several reasons may now answer `seat_taken` before `per_user_limit` or `invalid_seat`; all are still 4xx declines.
* **Kill switch:** `SEAT_HINTS_MODE=off`.
* **Benefit:** not yet measured on the live deployment (see the live report). A local H2 comparison showed no difference, which is expected because H2 has no network round trip.

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
* A health-monitor circuit breaker it added depended on Spring's default single-thread scheduler that the hold sweeper could block for 60 s; the local tests could not show this (the in-memory database never blocks), and the live outage run did. It was fixed with a scheduler pool and a regression test.
* Two of its predictions were wrong and were corrected after measurement: it said the full-size run would take 11-15 minutes and then about 35 (it took 21 minutes and 37,854 calls; the first figure counted only the stampede, the second over-extrapolated), and it said readiness failing would leave the app serving `503` for ordinary requests (behind Render's health check the platform removed the instance instead).
* An idempotency gap (the same key reused on a different show returned the first show's reservation) survived the first design and was found while the burst suite was being hardened; it is now fixed with a regression test.

**What is verified by running, and what is not:**
* Verified: 53 automated tests, two full local burst runs (default and short hold TTL), a hard-crash restart test, eight live burst runs against the Render deployment (about 152,000 calls, no 5xx from the application, every counter reconciled), including one full-size run, and a live database-outage probe with a passing audit (see the live report).
* Verified on the current build: a third scale-0.5 burst (19,788 calls) passed all checks with the circuit breaker live - it never opened under load - and no request was lost.
* Verified on the current build: the full-size suite (scale 1.0) passed cleanly - 37,808 calls, all checks, zero 5xx, zero transport failures - after the Tomcat keep-alive fix (300 s timeout, unlimited requests per connection).
* Reported, not hidden: two live runs on the pre-fix build each lost one request between the client and the application (a client-side timeout at scale 0.5; a single `520` in the first full-size run). Neither left a trace in server counters or state, and the failure has not recurred in three large runs since the keep-alive change - consistent with a proxy/keep-alive race, but the cause was never identified directly.
* Not verified: a confirm winning the expiry race on the live service, a partition under heavy load, and building the Docker image locally (Docker was not available; Render builds the image on every deploy).

## Next

1. **Postgres operations:** set `lock_timeout` and `statement_timeout` on connections so one stuck lock cannot hold a pooled connection; use a paid, highly available database (the free one expires after 30 days).
2. **Scale out safely:** move counters to a shared store or scrape per instance and aggregate, then run several application instances against one database.
3. **Real identity:** replace the demo token issuer with an identity provider and short-lived tokens.
5. **Abuse protection:** rate limiting per user and per IP, and a cap on the number of per-show gauges.
6. **Observability:** distributed tracing, a Grafana dashboard, and alert rules for the six paging conditions above.
7. **Capacity:** run the full-size burst against a larger instance to measure real headroom; the free tier handles about 25-35 requests/s. Also find the cause of the single unexplained transport failure seen in one scale-0.5 run.
