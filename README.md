# Seat Reservation at Scale

JSON HTTP API that sells assigned seats and never double-sells, over-allocates a user, or double-charges a retry.
Java 17, Spring Boot 3.2, JDBC + H2 (embedded, file-backed), JWT auth, Micrometer/Prometheus, JSON logs.

**Live URL:** _<fill in after deploy>_  ·  **Metrics:** `<URL>/actuator/prometheus`  ·  **Logs:** _<platform log URL / recording>_

## Run

```bash
make build && make run              # http://localhost:8080
docker compose up --build           # same thing, containerized
make test                           # integration tests (storms, limits, idempotency, cancel)
make burst URL=http://localhost:8080   # or: ./burst.sh https://<live-url>
```

Env: `PORT`, `JWT_SECRET`, `ADMIN_SECRET`, `PER_USER_LIMIT` (default 4), `DATABASE_URL` (JDBC URL), `DB_POOL_SIZE`, `HOLD_TTL_SECONDS`, `HOLD_SWEEP_MS`.
Defaults for `JWT_SECRET`/`ADMIN_SECRET` are dev-only; set both in any deployment.

## Auth (demo issuer)
The challenge has no identity provider, so `POST /auth/token` mints JWTs:
```bash
curl -XPOST $URL/auth/token -d '{"user_id":"alice"}' -H 'Content-Type: application/json'                    # user token
curl -XPOST $URL/auth/token -d '{"user_id":"ops","admin_secret":"$ADMIN_SECRET"}' -H 'Content-Type: application/json'  # admin token
```
Send `Authorization: Bearer <token>`. Identity is **only** the token subject; a `user_id` in a request body is ignored.
`POST /shows` needs an admin token (403 otherwise); reserve/cancel/get-reservation need any valid token (401 otherwise).

## API
| Endpoint | Notes |
|---|---|
| `POST /shows` (admin) | `{name, seats[], price_paise, per_user_limit?}` -> 201, all seats `available`. Duplicate/invalid seats -> 400. |
| `GET /shows/{id}` | per-seat status + `available/held/confirmed/total_seats` (also under `counts`). Derived from one query, so the invariant holds in every response. |
| `POST /shows/{id}/reserve` | body `{seats[], idempotency_key}` or header `Idempotency-Key`. **201** first time, **200** + `Idempotent-Replay: true` for a retry. Declines are **409** with `error` = `seat_taken` \| `per_user_limit` \| `idempotency_conflict` \| `contention`; unknown seat 400; unknown show 404. |
| `POST /shows/{id}/hold` | same body/rules as reserve, but seats go `held` for `HOLD_TTL_SECONDS` (default 300); 201 `{status:"held", expires_at}`. Counts toward the per-user limit. |
| `POST /reservations/{id}/confirm` | owner only; `held` -> `confirmed` while the hold is alive (idempotent); 409 `hold_expired` otherwise. |
| `POST /reservations/{id}/cancel` | owner only (403 otherwise), idempotent, releases the seats. |
| `GET /reservations/{id}` | owner only. |
| `GET /health/live`, `GET /health/ready` | readiness runs `SELECT 1` on a dedicated pool; 503 if the DB is unreachable. |
| `GET /actuator/prometheus` | metrics, below. |

Reserve response (201): `{reservation_id, show_id, user_id, seats, amount_paise, status:"confirmed"}`. Money is integer paise.

**Multi-seat policy: all-or-nothing.** If any requested seat is taken, nothing is held and the response is 409 `seat_taken`.
**Release model:** `reserve` confirms immediately and can be cancelled; the optional `hold` flow adds a TTL: an expiry sweeper (every `HOLD_SWEEP_MS`, default 5s) returns unconfirmed holds to `available`. Cancel works on held or confirmed reservations.

## Metrics
`reservations_confirmed_total`, `reservations_declined_total{reason=seat_taken|per_user_limit|idempotent_replay|idempotency_conflict|contention|invalid_seat|hold_expired}`,
`reservations_held_total`, `reservations_expired_total`, `reservations_cancelled_total`, `reservation_duration_seconds`, gauges `seats_available|held|confirmed{show_id}` and `seats_*_total`.
`burst.py` asserts each counter delta equals what the client observed, and that the gauge equals `GET /shows`.

## Logs
One JSON line per request (`method, path, status, duration_ms, user_id, request_id`) plus business events
(`reservation confirmed/cancelled` with `reservation_id`, `show_id`). `X-Request-ID` is accepted or generated, echoed in the response, and present on every line via MDC.

## Burst tool
`./burst.py <BASE_URL> [--requests 20000 --users 2000 --seats 500 --hot 5 --admin-secret S]` (stdlib only; `ADMIN_SECRET` env also works).
Phases: (1) 500 users on one seat; (2) 20k-request stampede on 5 hot seats with ~10% same-key retries while polling the invariant;
(3) 50 parallel same-key requests + same-key-different-seats; (4) one user, 10 parallel reserves, limit 4; (5) cancel/rebook/ownership/spoofing; (6) metrics reconciliation.
Prints the outcome distribution and exits non-zero on any failed check.

## Deploy
`render.yaml` is a Render blueprint (Docker, health check `/health/ready`, generated secrets). Any Docker host works.
Free tiers have an ephemeral disk: H2 data resets on restart/redeploy (fine for the challenge; set `DATABASE_URL` to a persistent volume path to keep it).
