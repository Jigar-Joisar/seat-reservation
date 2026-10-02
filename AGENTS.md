# AGENTS.md

Working reference for developers and AI agents. If this disagrees with older notes, the code wins; this file was rewritten after the JDBC rewrite and matches it.

## What this is
Seat reservation HTTP API (Spring Boot 3.2, Java 17, plain JDBC on embedded H2). Correctness under concurrency is the whole point. Overview and usage: `README.md`; API: `docs/API.md`; design rationale: `WRITEUP.md`.

## Commands
| Task | Command |
|---|---|
| run | `./run.sh` (env overrides, `.env`, `FRESH_DB=1`) |
| build | `mvn -q clean package -DskipTests` |
| tests | `mvn -q test` (46 tests, ~1 min; first run needs network for surefire) |
| load test | `./burst.sh <URL>` |
| lint spec | `make lint-api` |

Only one process may use `./data` at a time (H2 file lock).

## Code map
* `service/ReservationService`: all booking logic. `reserve/hold` -> `doReserve`, `confirm`, `cancel`, `expireIfOverdue`.
* `service/HoldExpirySweeper`: scheduled; calls `expireIfOverdue` per overdue hold.
* `service/ShowService`: create/get show. `StatsCache` + `ReservationMetrics`: gauges and counters.
* `filter/AuthFilter`: JWT check. Protected = `POST /shows` (admin), `/shows/*/reserve|hold`, `/reservations/**`. If you add a protected route, update this filter, `static/openapi.yaml` and the contract test.
* `api/GlobalExceptionHandler`: maps everything to `{error,message}`. Business declines are `ApiException` (409), lock/timeouts map to 409 `contention`.
* `schema.sql`: tables `shows`, `seats`, `reservations`, `user_show_allocations`.

## Invariants (do not break)
1. **Seat claim** is a conditional UPDATE: `... SET status=? ... WHERE show_id=? AND seat_number=? AND status='available'`; rowcount 0 means taken (409). Never read-then-write.
2. **Lock order everywhere**: allocation row (`user_show_allocations ... FOR UPDATE`) -> reservation row (`FOR UPDATE`) -> seats (sorted by name). All paths (reserve, hold, confirm, cancel, expiry) follow it, so no deadlocks.
3. The allocation lock serializes one user's operations per show, which makes the per-user limit and the idempotency re-check race-free. `seats_held` must equal the user's non-available seats (tests check this).
4. Releases (cancel, expiry) are keyed on `reservation_id` and the expected status, so they never touch a seat now owned by someone else.
5. Idempotency: `UNIQUE(user_id, idempotency_key)`; replay returns 200, first success 201; declines do not consume the key.
6. Transactions are READ_COMMITTED (Hikari setting). Do not switch to SERIALIZABLE; it causes lock storms on H2.
7. Money is `long` paise; Jackson rejects floats (`accept-float-as-int: false`); price is capped so `price * seats` cannot overflow.
8. Business outcomes must never be 5xx.

## Conventions
* JSON is snake_case (Jackson global strategy). DTOs are records in `api/Dtos`.
* `AbstractApiTest` has HTTP helpers and `assertDatabaseConsistent(showId)`; call it at the end of any concurrency test.
* After changing an endpoint run `OpenApiContractTest` (spec must match controllers) and keep `make lint-api` clean.
* Tests use profile `test` (in-memory H2, hold TTL 2 s, sweep 300 ms).

## Known limits
Single instance (embedded H2); demo token issuer instead of a real IdP; ephemeral disk on free hosting tiers; Docker image not built in CI yet.
