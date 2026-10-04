# Seat Reservation at Scale

A small JSON HTTP service that sells **assigned seats** for an event and stays correct when thousands of buyers hit the same show at once:

* a seat is never sold twice (500 buyers on one seat: exactly one wins, 499 get a clean `409`)
* a user never exceeds the per-show limit, even with parallel requests
* a retried request never books or charges twice (idempotency keys)
* declines are `4xx` domain outcomes, never `5xx`
* `available + held + confirmed == total_seats` at every moment
* the database alone decides every sale; an optional in-process cache only makes rejections of already-taken seats faster (see [Performance](#performance-seat-hints))

Java 17 · Spring Boot 3.2 · plain JDBC · PostgreSQL in deployment (H2 locally) · JWT auth · Prometheus metrics · structured JSON logs · Swagger UI

| | |
|---|---|
| Repository | https://github.com/Jigar-Joisar/seat-reservation |
| Live service | https://seat-reservation-9zhl.onrender.com (Swagger UI at `/`) |
| Health | `/health/live`, `/health/ready` |
| Metrics | `/actuator/prometheus` |
| Recent logs (public, scrubbed) | `/ops/logs` ([what it exposes](#public-log-access)) |
| Test evidence | [docs/LIVE-TEST-REPORT.md](docs/LIVE-TEST-REPORT.md) |

The live service runs on Render's free tier: expect a cold start of up to a minute after 15 idle minutes, and roughly 25-47 requests/s of throughput.

Design write-up (atomic mechanism, locking, idempotency, holds, CAP, paging, AI usage): [WRITEUP.md](WRITEUP.md) · Full API guide: [docs/API.md](docs/API.md) · Live test report: [docs/LIVE-TEST-REPORT.md](docs/LIVE-TEST-REPORT.md)

### Verified results at a glance

Everything below was run against the live Render deployment (raw output, JSON reports and Prometheus snapshots in [`docs/evidence/`](docs/evidence/)); failed runs are kept and explained in the [report](docs/LIVE-TEST-REPORT.md).

| What | Result |
|---|---|
| Full-size burst (37,808 calls, 13 adversarial scenarios, 1,227 s) | all checks passed, no 5xx, sell-out exactly 500/500 |
| Half-size bursts | three clean runs (650-704 s) and one failed run, kept and diagnosed |
| Hot seat, 500 buyers | exactly one `201`, 499 `409` |
| Parallel duplicates (150 users x 3 identical requests) | 150 created + 300 replays, no double charge |
| Database outage (Postgres suspended about 4 min) | liveness `200` throughout, breaker opened in 8 s, 62 fast `503` + `Retry-After`, nothing booked, audit passed, self-recovery about 1 s after the database answered |
| Seat-hint cache, half size (hints on vs off, same suite) | 19 % faster (568 s vs 704 s), median latency 4.0 s to 2.6 s, every check passing |
| Seat-hint cache, full size (37,894 calls) | all checks passed, no 5xx, 965 s vs 1,227 s without hints (21 % faster), stampede 34 to 47 req/s, median latency 4.2 s to 2.0 s; p99 unchanged (about 20 s) |
| Automated tests | 69 (unit, integration, concurrency stress, contract) |

Known limits, stated plainly: the hint cache is exact only for a single instance; the outage probe was not repeated with the cache on; one request was lost between client and application in each of two early runs and the cause was never identified (details in the report).

---

## Quick start

Requirements: **Java 17+**, **Maven 3.6+**, **Python 3** (only for the burst tool). Docker is optional.

```bash
git clone https://github.com/Jigar-Joisar/seat-reservation.git
cd seat-reservation
./run.sh                 # builds if needed, serves http://localhost:8080
```

Then open **http://localhost:8080/** for Swagger UI:
1. `POST /auth/token` with `{"user_id":"alice"}`, copy the `token`.
2. Click **Authorize**, paste the token (no `Bearer ` prefix).
3. Try the endpoints. For an admin token (needed for `POST /shows`) also send `"admin_secret":"dev-admin-secret"`.

Other ways to run:

```bash
docker compose up --build        # containerized, same port
make build && make run           # via Makefile
mvn spring-boot:run              # from source
```

### Configuration

Every setting has a default. Override with environment variables, a `.env` file next to `run.sh` (git-ignored), or inline: `PORT=9090 ADMIN_SECRET=s3cret ./run.sh`.

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `JWT_SECRET` | `dev-only-jwt-secret-change-me` | token signing secret. **Set in any deployment.** |
| `ADMIN_SECRET` | `dev-admin-secret` | secret that turns a token request into an admin token. **Set in any deployment.** |
| `PER_USER_LIMIT` | `4` | default max seats per user per show (a show can override it) |
| `HOLD_TTL_SECONDS` | `300` | how long a `hold` lasts before the seats are released |
| `HOLD_SWEEP_MS` | `5000` | how often expired holds are swept |
| `SEAT_HINTS_MODE` | `local` | `local` = in-process advisory `seat_taken` hints (Caffeine); `off` = every request goes to the database (kill switch). `SEAT_HINTS_MAX` (500000) and `SEAT_HINTS_TTL_SECONDS` (60) bound the cache |
| `DB_PROBE_MS` | `2000` | how often the database circuit breaker probes the database (3 failures open it) |
| `DB_POOL_SIZE` | `32` (`16` on Render) | JDBC connection pool size |
| `DATABASE_URL` | file-backed H2 in `./data` (`WRITE_DELAY=0` for crash durability) | JDBC URL **or** `postgres://user:pass@host:port/db` (Render/Heroku style, auto-converted) |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `sa` / empty | credentials for `jdbc:` URLs that don't embed them |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75 -XX:+UseSerialGC` | JVM flags (`run.sh`) |
| `PUBLIC_LOGS` | `true` | serve `GET /ops/logs`; set `false` to disable it (404) |
| `FRESH_DB=1` | off | `run.sh` only: delete `./data` before starting |

---

## Using the API

Authentication is a **demo token issuer** (the challenge has no identity provider): `POST /auth/token` returns a 24 h JWT; send it as `Authorization: Bearer <token>`. Identity always comes from the token, never from a request body.

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /auth/token` | none | mint a user token, or an admin token with `admin_secret` |
| `POST /shows` | admin | create a show `{name, seats[], price_paise, per_user_limit?}` |
| `GET /shows/{id}` | none | seat map + counts |
| `POST /shows/{id}/reserve` | user | book seats, **confirmed immediately** |
| `POST /shows/{id}/hold` | user | hold seats for a limited time |
| `POST /reservations/{id}/confirm` | owner | turn a hold into a booking |
| `POST /reservations/{id}/cancel` | owner | release the seats |
| `GET /reservations/{id}` | owner | view a reservation |
| `GET /health/live`, `GET /health/ready` | none | liveness; readiness (checks the DB, 503 when down) |
| `GET /actuator/prometheus` | none | metrics |
| `GET /ops/logs` | none | recent structured log events, allow-listed and scrubbed |

```bash
URL=http://localhost:8080
ADMIN=$(curl -s -XPOST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"ops","admin_secret":"dev-admin-secret"}' | jq -r .token)
USER=$(curl -s -XPOST  $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}' | jq -r .token)
SHOW=$(curl -s -XPOST $URL/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
        -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}' | jq -r .id)
curl -s -XPOST $URL/shows/$SHOW/reserve -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' \
        -d '{"seats":["A1"],"idempotency_key":"order-1"}'
```

Key behaviours (details and every status code in [docs/API.md](docs/API.md)):

* **Success `201`**: `{reservation_id, show_id, user_id, seats, amount_paise, status:"confirmed"}`. Money is integer paise.
* **Declines are `409`** with `{error, message}`: `seat_taken`, `per_user_limit`, `idempotency_conflict`, `hold_expired`, `contention`.
* **Idempotency**: send `idempotency_key` (body) or `Idempotency-Key` (header). Same key + same seats returns the original reservation (`200`, header `Idempotent-Replay: true`). Same key + different seats is `409`. Keys are per user.
* **Multi-seat is all-or-nothing**: if any seat is unavailable, nothing is taken.
* **Holds** (`/hold`) reserve seats for `HOLD_TTL_SECONDS`; confirm before then or they return to `available` automatically.
* **Cancel** works on held or confirmed reservations and never touches a seat that has since been re-booked by someone else.

OpenAPI 3 spec: `GET /openapi.yaml` (source: `src/main/resources/static/openapi.yaml`). It is lint-clean (`make lint-api`) and a test fails if it drifts from the controllers.

---

## Testing

| Command | What it runs |
|---|---|
| `make test` (`mvn test`) | 69 tests, mostly integration tests against a real server on a random port (in-memory H2) |
| `make burst URL=http://localhost:8080` | the burst suite below against a running instance (local or deployed) |
| `make lint-api` | Redocly lint of the OpenAPI spec (needs Node/npx) |

The test suites (`src/test/java/com/seatreservation`):

* `ReservationApiTest`: happy paths, hot-seat storm, per-user limit, idempotency, cancel/rebook, holds, health, metrics
* `ValidationAndAuthTest`: input validation (including fractional and overflowing money), error shape, token forgery/expiry/`alg=none`, admin vs user, ownership, request ids
* `ConcurrencyStressTest`: randomized mixes of reserve/hold/confirm/cancel/replay by 40 users, crossed multi-seat orders (deadlock check), confirm racing expiry, confirm racing cancel, seat recycling, exact sold-minus-cancelled accounting, metrics reconciliation. After every scenario a **database cross-check** verifies seat/reservation/allocation consistency.
* `ReadinessFailClosedTest`: readiness returns 503 when the DB connection is lost while liveness stays 200
* `LogsEndpointTest`: the log endpoint finds a request by its `X-Request-ID`, never returns tokens, secrets, headers, stack traces or unknown fields, and scrubs a JWT planted in a URL
* `OpenApiContractTest`: spec documents exactly the real endpoints, security/401/403 declarations match, error codes are real
* `DbGuardTest` / `SchedulerIsolationTest`: the database circuit breaker answers `503` + `Retry-After` immediately while liveness and token issuing stay up; the hold sweeper and the health monitor never share a scheduler thread
* `SeatHintsTest` / `LocalSeatHintsTest` / `SeatHintsOffTest`: the hint cache never changes an answer (empty cache after a restart, eviction, TTL expiry, stale-epoch writes dropped, cancel and hold expiry free the seat immediately, a parallel duplicate of a winning request still replays, release churn leaves no stale hint, `SEAT_HINTS_MODE=off` behaves identically). Every concurrency test also asserts that no *available* seat carries a hint.

### Database-outage probe

`./outage_probe.py <BASE_URL> --admin-secret S --duration 300 [--insecure] [--report out.json]` records, once per second, `/health/live`, `/health/ready` and one reservation attempt on a fresh seat while you stop and restart the database. Expected: live stays `200`, ready flips to `503`, reservations return `503 service_unavailable` with `Retry-After` (never a `201` that was not committed, never a partial booking), and the service recovers by itself when the database returns. It finishes with an audit (every seat that returned `201` is confirmed, the show invariant holds).

### Burst tool (load + adversarial correctness suite)

```bash
./burst.sh http://localhost:8080                 # or https://<live-url>; the scheme is optional
./burst.py <BASE_URL> [--scale 1.0] [--only 2,5,8] [--admin-secret S] [--hold-ttl N] [--seed 7] [--report run.json] [--insecure]
HOLD_TTL_SECONDS=3 ./run.sh                      # start the server like this to also run the hold-expiry scenarios
```

Stdlib-only Python; exit code is non-zero if any check fails. It verifies everything **from the outside over HTTP**: outcome distributions, the show invariant, readiness during load, latency percentiles, a full **audit** (every reservation is fetched and cross-checked against the seat map: no seat in two live reservations, seat map equals the union of live reservations, statuses agree, nobody above the limit) and finally metric reconciliation. `--scale 0.2` gives a quick smoke run (about 10 s); `--only` runs selected scenarios. Each run uses unique user ids, so it can be repeated against the same instance. Every scenario prints its start time, duration and call count, and the summary ends with a per-scenario timing table and the total run time; `--report run.json` also writes these (plus outcomes and latency percentiles) as JSON for evidence. `--insecure` skips TLS verification for machines with a broken local CA bundle.

| # | Scenario | What must hold |
|---|---|---|
| 1 | 500 users, one seat | exactly one 201, 499 x 409 `seat_taken`, audit clean |
| 2 | 20k-request stampede (2000 users, 5 hot seats, ~10 % same-key retries) with live polling | zero 5xx, invariant on every sample, `/health/ready` always 200, hall sold out exactly, one owner per hot seat, latency p50/p95/p99 |
| 3 | idempotency: 50 parallel same-key requests; 150 users x 3 identical parallel requests; same key different seats; same key string across users | one reservation per key, 150 created + 300 replays, no double charge, conflicts 409 |
| 4 | per-user limit: one user x 10 parallel; mixed reserve+hold; 150 users x 8 parallel distinct seats | exactly 4 each, never 5 |
| 5 | multi-seat deadlock storm: 300 users, random 2-4 seats in random order on 12 seats | only 201/409, taken seats == seats in winning requests (failed requests leak nothing) |
| 6 | sell-out: 6000 requests for 200 seats | every seat sold exactly once, number of 201s == confirmed seats |
| 7 | recycling: 200 users reserve/cancel the same 3 seats | every win cancelled cleanly, all seats available at the end |
| 8 | chaos mix: 100 users x 80 random reserve / hold / confirm / cancel / replay / reads | only 200/201/409, audit clean, all unconfirmed holds expire (short TTL) |
| 9 | confirm racing cancel on the same hold, 30 rounds | cancel always wins the final state, never a mixed result |
| 10 | hold expiry (short TTL only): confirms fired around the expiry instant | a confirm that returned 200 is never undone; every other hold expires; limit and seat are freed; confirmed seats survive sweeps |
| 11 | hostile input under load: no/garbage/tampered token, user creating a show, malformed JSON, wrong types, injection-looking seats, fractional and giant prices, unknown routes and methods | the expected 4xx every time, zero 5xx, show unchanged |
| 12 | two shows at once, same users and seat names | independent winners and limits per show |
| 13 | ownership, spoofed `user_id`, stale cancel, idempotent confirm | 403 for non-owners, token identity wins, no resurrection of re-booked seats |
| 14 | metrics reconciliation (needs a service with no other traffic) | confirmed/held counters and every decline reason equal what the client observed |

Sanity check of the checker itself: removing the `AND status = 'available'` guard from the seat UPDATE (a deliberate double-sell bug) makes scenarios 1 and 5 fail on "exactly one 201" and "no seat appears in two live reservations". The plain `available + held + confirmed == total` invariant would not have caught it, which is why the audit exists.

---

## Observability

* **Health**: `/health/live` (process), `/health/ready` (database reachable, checked on a dedicated 2-connection pool so a saturated request pool never flaps readiness; 503 when down).
* **Database outage behaviour**: a monitor probes the database every `DB_PROBE_MS` (default 2000) on that dedicated pool. After 3 consecutive failures the circuit opens and requests that need the database (`/shows*`, `/reservations*`) get an immediate `503 service_unavailable` with `Retry-After: 5` instead of waiting out the 60 s connection timeout; the first successful probe closes it. Liveness, token issuing, metrics and logs keep working. Nothing is ever booked while the database is unreachable.
* **Metrics** (`/actuator/prometheus`): `seat_hints_total{event=hit|miss|learned|dropped_stale|released}` and `seat_hints_size` (absent when `SEAT_HINTS_MODE=off`), `reservations_confirmed_total`, `reservations_held_total`, `reservations_cancelled_total`, `reservations_expired_total`, `reservations_declined_total{reason}`, `reservation_duration_seconds`, gauges `seats_available|held|confirmed{show_id}`.
* **Logs** (stdout JSON, plus [a public read-only view](#public-log-access)): one JSON line per request (`method, path, status, duration_ms, user_id, request_id`) plus business events (`reservation confirmed|held|cancelled`, `hold expired`) with `reservation_id` and `show_id`. `X-Request-ID` is accepted (if safe) or generated, echoed on every response, and attached to every log line.

### Public log access

`GET /ops/logs?limit=200&request_id=<id>` returns the most recent events (newest last) from an in-memory buffer of 2000, so a reviewer can follow a request without platform access. For example, take the `X-Request-ID` header from any response and fetch its lines: the access line (`method`, `path`, `status`, `duration_ms`, `user_id`) and any business event (`reservation confirmed|held|cancelled`, `hold expired`).

Why it is safe to expose:

* **Allow-list, not block-list.** Only these fields are ever returned: `timestamp, level, logger, message, request_id, method, path, status, duration_ms, user_id, reservation_id, show_id, seats, released`. Request and response headers, bodies, `Authorization` values, the admin secret, JWTs and stack traces are never captured because the code never logs them and the endpoint could not return them anyway.
* **Scoped sources.** Only the access log and this application's own loggers enter the buffer; Spring, Hikari and JDBC driver output (which can print connection details) does not.
* **Scrubbing.** String values are additionally rewritten to `[redacted]` if they look like a JWT, a `Bearer` credential, a `jdbc:`/`postgres://` URL or `secret=`/`password=`/`token=` pairs, and are truncated to 300 characters. A test plants a fake JWT in a URL to prove it.
* **What remains visible:** user ids (the demo ids chosen by the caller), seat names, reservation and show ids, request paths. Treat user ids as personal data if you plug in a real identity provider, or turn the endpoint off with `PUBLIC_LOGS=false`.
* Reading the logs does not write log lines about itself, and the buffer is per instance and lost on restart. The platform's own log stream remains the durable record.

## Performance: seat hints

In a stampede about 97 % of requests are `409 seat_taken`, and each used to cost about eight database round trips. The service keeps an in-process cache (Caffeine) of seats it knows are taken, so a request for a taken seat is declined early.

* **It can only reject, never grant.** The database still makes every sale. An empty, evicted, expired, restarted or disabled cache just means the database decides, as it always did. A retry of a winning request still replays (`200`): the hint is read first, and the idempotency lookup still runs.
* **Releases clear it.** Cancel and hold expiry remove their seats from the cache right after the transaction, and a hint is dropped if a release happened while it was being learned. Entries also expire after `SEAT_HINTS_TTL_SECONDS` (60) and the cache holds at most `SEAT_HINTS_MAX` (500,000).
* **Kill switch:** `SEAT_HINTS_MODE=off` (no code change; the service restarts).
* **Measured on Render + Postgres:** the half-size suite took 568 s with hints against 704 s without, with all checks passing ([report](docs/LIVE-TEST-REPORT.md#seat-hints-live-ab-on-render-postgres-scale-05)). At full size it took 965 s against 1,227 s. The tail latency (p99, about 20 s) did not improve. One run per condition on shared free hardware, so treat the gains as estimates.
* **Behaviour note:** a request that is doomed for several reasons may answer `seat_taken` before `per_user_limit` or `invalid_seat`. All are still 4xx declines.
* **Single instance only.** A second instance (or the short overlap of two instances during a deploy) can release a seat without this instance's cache noticing, so a stale "taken" hint can live up to the TTL: an unfair `409`, never a double sale. If you run several instances, set `SEAT_HINTS_MODE=off` or replace the cache with a shared store (a Redis implementation of `cache/SeatHints` is the designed next step; see the [write-up](WRITEUP.md#seat-hints-performance-advisory-only)).

## Deployment

The `Dockerfile` is a multi-stage build (Maven, then a non-root JRE image with a readiness `HEALTHCHECK`). `render.yaml` is a Render blueprint: a Docker web service (health check `/health/live`, generated `JWT_SECRET`/`ADMIN_SECRET`) plus a free-tier Postgres wired via `DATABASE_URL`. `docker compose up --build` runs the same pairing locally: app + a real Postgres container.

Health check choice: Render takes an instance out of rotation after 15 s of failed checks and restarts it after 60 s. Pointing the check at `/health/ready` would turn a database outage into Render's own `502`s for every client, so the platform check uses `/health/live` and `/health/ready` is for your monitoring. (A service created by hand in the dashboard needs this set under Settings > Health Check Path; `render.yaml` only applies to Blueprint-managed services.)

Checklist: the service listens on `$PORT`; with the blueprint, `DATABASE_URL`, `JWT_SECRET` and `ADMIN_SECRET` are set automatically. Optional: `SEAT_HINTS_MODE` (`local` by default). Rotate `JWT_SECRET`/`ADMIN_SECRET` if they were ever shared outside the team. The service is single-instance by design (a second instance would not share in-memory metric counters); see [WRITEUP.md](WRITEUP.md) for the reasoning.

## Restarts, crashes and cold starts

Verified by killing the process with `kill -9` and restarting against the same database file:

* **Startup**: ready in about 3 s on a laptop (schema is applied idempotently with `CREATE TABLE IF NOT EXISTS`, so starting on an existing database is safe). Readiness only turns 200 once the database answers.
* **Durability**: the default JDBC URL uses `WRITE_DELAY=0`, so a committed reservation is flushed before the `201` is returned. With H2's default (500 ms delay) a hard crash could silently lose the last half second of confirmed bookings, i.e. tell a buyer they own a seat that is later sold again. Cost: about 20 % lower peak throughput in our test (about 820 vs 1030 req/s on a laptop), all burst checks still pass.
* **Holds that expire while the service is down** are released by the sweeper right after startup; a confirm attempted in that window is rejected by the expiry timestamp even before the sweep runs.
* **Idempotent retries after a restart** still return the original reservation (200), so a client that retries after a crash never double-books.
* **Tokens** stay valid across restarts as long as `JWT_SECRET` is unchanged (set it explicitly; if it is not set the dev default is used).
* **Metrics gauges** are re-registered for existing shows at startup (counters restart from zero, as usual for Prometheus).
* **Graceful shutdown**: in-flight requests finish (`server.shutdown=graceful`).

On hosting with an **ephemeral disk** (free tiers) the local-H2 setup starts with an empty database after a restart; with `DATABASE_URL` pointing at Postgres, shows and reservations survive restarts and redeploys. Note Render's free Postgres **expires 30 days after creation** (recreate it to extend). Free web tiers also sleep when idle; the first request after a sleep waits for the cold start, and `burst.py` retries readiness for about two minutes.

## Project layout

```
src/main/java/com/seatreservation/
  controller/   Show/Reservation, Auth, Health endpoints
  service/      ReservationService (atomic decisions), ShowService, HoldExpirySweeper, DbHealthMonitor (circuit breaker), metrics, stats cache
  cache/        SeatHints (advisory seat_taken cache): Local (Caffeine) and Noop implementations
  filter/       RequestIdFilter, AccessLogFilter, AuthFilter (JWT), DbGuardFilter
  api/          DTO records, ApiException, GlobalExceptionHandler
  config/       JwtService, PostgresUrlInitializer (postgres:// -> JDBC)
  logging/      RingBufferAppender (recent-events buffer behind /ops/logs)
src/main/resources/  application.yml, schema.sql, static/ (Swagger UI + openapi.yaml)
src/test/            integration + stress + contract tests
burst.py / burst.sh  load tool     outage_probe.py  database-outage probe     run.sh  local runner
docs/API.md  API guide     docs/LIVE-TEST-REPORT.md  live evidence     docs/evidence/  raw runs
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `The file is locked: .../data/seats.mv.db` or `Database may be already in use` | another instance (for example `mvn spring-boot:run` in another terminal or your IDE) is using `./data`. Stop it, or use `DATABASE_URL`/`FRESH_DB=1`. |
| `Port 8080 was already in use` | `PORT=9090 ./run.sh` |
| `401 unauthorized` on every call | missing `Authorization: Bearer <token>`; tokens from another instance with a different `JWT_SECRET` are rejected |
| `403` on `POST /shows` | you used a user token; request an admin token with `admin_secret` |
| `burst.py` cannot reach the service | pass the base URL (`localhost:8080` works); on a cold free-tier host it retries readiness for ~2 minutes |
| Occasional `409 seat_taken` for a seat that looks free, only with several instances | a stale hint from another instance's release; it clears within `SEAT_HINTS_TTL_SECONDS`. Set `SEAT_HINTS_MODE=off` when running more than one instance |
| Swagger UI is blank | the page loads Swagger assets from unpkg.com; check internet access |

## License
MIT
