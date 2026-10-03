# API guide

Base URL: `http://localhost:8080` locally (or your deployed URL). Interactive docs: open the base URL in a browser (Swagger UI); the machine-readable spec is `GET /openapi.yaml`.

All request and response bodies are JSON with **snake_case** fields. Money is an **integer number of paise** (never a float).

## 1. Authentication

The service has no external identity provider, so it ships a **demo token issuer**. Tokens are JWTs (HS256), valid 24 hours.

| Who | How to get a token | What it allows |
|---|---|---|
| User | `POST /auth/token` `{"user_id":"alice"}` | reserve, hold, confirm, cancel, view own reservations |
| Admin | `POST /auth/token` `{"user_id":"ops","admin_secret":"<ADMIN_SECRET>"}` | everything a user can do **plus** `POST /shows` |

```bash
URL=http://localhost:8080
USER=$(curl -s -XPOST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}' | jq -r .token)
ADMIN=$(curl -s -XPOST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"ops","admin_secret":"dev-admin-secret"}' | jq -r .token)
```

Send the token on every protected call:

| Header | Value | Required on |
|---|---|---|
| `Authorization` | `Bearer <token>` | `POST /shows`, `reserve`, `hold`, `confirm`, `cancel`, `GET /reservations/{id}` |
| `Content-Type` | `application/json` | every request with a body |
| `Idempotency-Key` | any string up to 128 chars | optional alternative to the body field `idempotency_key` |
| `X-Request-ID` | `[A-Za-z0-9._-]{1,64}` | optional; echoed back and included in every log line |

Rules:
* The caller's identity is the token's subject. A `user_id` in a request body is **ignored**.
* Naming yourself `admin` gives no privileges; only the correct `admin_secret` does.
* Missing, expired, tampered or wrongly signed token: **401**. Valid token without permission: **403**.
* Public (no token): `POST /auth/token`, `GET /shows/{id}`, `/health/*`, `/actuator/prometheus`, `/openapi.yaml`, `/`.

## 2. Endpoints

### `POST /shows` (admin)
```bash
curl -s -XPOST $URL/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A4"],"price_paise":25000,"per_user_limit":4}'
```
| Field | Type | Rules |
|---|---|---|
| `name` | string | required, max 255 |
| `seats` | string[] | required, unique, each `[A-Za-z0-9_-]{1,32}`, max 100000 |
| `price_paise` | integer | required, 1 to 1,000,000,000,000; fractions rejected |
| `per_user_limit` | integer | optional, 1 to 1000, default `PER_USER_LIMIT` (4) |

Returns **201** with the show: `id`, `name`, `price_paise`, `per_user_limit`, `available`, `held`, `confirmed`, `total_seats`, `counts{}` and `seats:[{seat,status}]`.

### `GET /shows/{id}` (public)
Seat map sorted by name. Seat status is `available`, `held` or `confirmed`. The counts always satisfy `available + held + confirmed == total_seats`. Unknown id: 404 `show_not_found`.

### `POST /shows/{id}/reserve` (user)
Books seats **confirmed immediately**.
```bash
curl -s -XPOST $URL/shows/$SHOW/reserve -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' \
  -d '{"seats":["A1","A2"],"idempotency_key":"order-123"}'
```
* **201** `{reservation_id, show_id, user_id, seats, amount_paise, status:"confirmed"}`. `amount_paise = price_paise * number_of_seats`; `seats` is returned sorted.
* **200** + header `Idempotent-Replay: true`: a retry with the same key and same seat set. Same body as the original; nothing extra is booked.
* **409** `seat_taken`, `per_user_limit`, `idempotency_conflict`, `contention`.
* **400** `invalid_request` (bad JSON, missing key, empty or duplicate seats, bad seat name) or `invalid_seat` (no such seat). **404** `show_not_found`.

**Multi-seat policy: all-or-nothing.** If any requested seat is unavailable, nothing is taken and the response is 409 `seat_taken`. This holds under concurrency.

### `POST /shows/{id}/hold` (user)
Same body, rules, status codes and idempotency as `reserve`, but seats become `held` for `HOLD_TTL_SECONDS` (default 300) and the response is `status:"held"` with `expires_at`. Held seats count toward the per-user limit and cannot be taken by anyone else.

### `POST /reservations/{id}/confirm` (owner)
`held` to `confirmed`. Idempotent (an already confirmed reservation returns 200 again). If the hold expired or was cancelled: **409** `hold_expired`. Not yours: 403. Unknown: 404.

### `POST /reservations/{id}/cancel` (owner)
Releases the seats of a `held` or `confirmed` reservation and frees the user's limit. Idempotent. Only seats still owned by this reservation are released, so a stale cancel can never affect a seat someone else has since booked.

### `GET /reservations/{id}` (owner)
Returns the reservation. `status` is one of `held`, `confirmed`, `cancelled`, `expired`.

### Ops
| Endpoint | Purpose |
|---|---|
| `GET /health/live` | process is up (does not touch the DB) |
| `GET /health/ready` | runs `SELECT 1`; **503** `{"status":"DOWN"}` if the database is unreachable |
| `GET /actuator/prometheus` | Prometheus metrics |
| `GET /ops/logs?limit=200&request_id=` | recent structured log events (newest last). Allow-listed fields only, no tokens, secrets, headers or stack traces; `404` when `PUBLIC_LOGS=false`. See the README section *Public log access*. |

## 3. Idempotency in detail
* The key identifies **one order**. Generate a fresh one per order and reuse it only to retry that order (after a timeout, a dropped connection, a 5xx or a 409 `contention`).
* Scope is **per user**: two users can use the same string without interfering.
* Same key + same seat set (order irrelevant): returns the original reservation (**200**, `Idempotent-Replay: true`).
* Same key + different seats: **409** `idempotency_conflict`.
* A declined request (for example `seat_taken`) does **not** consume the key; you may retry it later.
* Send the key in the body (`idempotency_key`) or in the `Idempotency-Key` header. If both are present they must match (else 400).

## 4. Errors
Every error is `{"error":"<code>","message":"...","details":{...}}` (`details` is optional).

| HTTP | `error` | When |
|---|---|---|
| 400 | `invalid_request` | malformed JSON, wrong types, missing field, bad idempotency key, duplicate seats |
| 400 | `invalid_seat` | seat does not exist in the show |
| 401 | `unauthorized` | token missing or invalid |
| 403 | `forbidden` | not admin, or not the owner |
| 404 | `show_not_found`, `reservation_not_found` | unknown id |
| 405 / 404 | `method_not_allowed` / `not_found` | wrong method or path |
| 409 | `seat_taken` | requested seat is held or confirmed |
| 409 | `per_user_limit` | would exceed the show's per-user limit (`details`: `limit`, `currently_held`, `requested`) |
| 409 | `idempotency_conflict` | key reused with different seats |
| 409 | `hold_expired` | confirming an expired or cancelled hold |
| 409 | `contention` | transient lock contention; retry with the same key |
| 503 | `service_unavailable` | the database is unreachable; nothing was booked; header `Retry-After: 5`; retry with the same idempotency key |
| 5xx | `internal_error` | a bug or an outage; should never occur for business outcomes |

## 5. End-to-end example
```bash
URL=http://localhost:8080
ADMIN=$(curl -s -XPOST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"ops","admin_secret":"dev-admin-secret"}' | jq -r .token)
ALICE=$(curl -s -XPOST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}' | jq -r .token)
BOB=$(curl -s -XPOST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"bob"}' | jq -r .token)

SHOW=$(curl -s -XPOST $URL/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"demo","seats":["A1","A2","A3"],"price_paise":25000}' | jq -r .id)

# alice books A1 (201), retries the same order (200 replay)
curl -s -XPOST $URL/shows/$SHOW/reserve -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d '{"seats":["A1"],"idempotency_key":"o-1"}'
curl -si -XPOST $URL/shows/$SHOW/reserve -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d '{"seats":["A1"],"idempotency_key":"o-1"}' | head -1

# bob tries the same seat (409 seat_taken)
curl -s -XPOST $URL/shows/$SHOW/reserve -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' -d '{"seats":["A1"],"idempotency_key":"b-1"}'

# bob holds A2, then confirms it
RID=$(curl -s -XPOST $URL/shows/$SHOW/hold -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' -d '{"seats":["A2"],"idempotency_key":"b-2"}' | jq -r .reservation_id)
curl -s -XPOST $URL/reservations/$RID/confirm -H "Authorization: Bearer $BOB"

# alice cannot cancel bob's reservation (403); bob can (200)
curl -s -XPOST $URL/reservations/$RID/cancel -H "Authorization: Bearer $ALICE"
curl -s -XPOST $URL/reservations/$RID/cancel -H "Authorization: Bearer $BOB"

curl -s $URL/shows/$SHOW | jq '{available,held,confirmed,total_seats}'
```

## 6. Metrics
`reservations_confirmed_total`, `reservations_held_total`, `reservations_cancelled_total`, `reservations_expired_total`, `reservations_declined_total{reason="seat_taken|per_user_limit|idempotent_replay|idempotency_conflict|contention|invalid_seat|hold_expired"}`, `reservation_duration_seconds`, gauges `seats_available|held|confirmed{show_id}` and `seats_available_total|held_total|confirmed_total`.
Reconciliation: confirmed + held counters match the 201 responses you saw, each decline reason matches the 409s of that kind, and `seats_available{show_id}` equals `available` from `GET /shows/{id}`.
