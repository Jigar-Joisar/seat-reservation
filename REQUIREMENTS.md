# Seat Reservation Service - Requirements Extraction

## Functional Requirements

### 1. Create Show (Admin)
**Endpoint:** `POST /shows`
**Request:**
```json
{
  "name": "friday-night",
  "seats": ["A1", "A2", "A3", "..."],
  "price_paise": 25000
}
```
**Response:** Created show with id and all seats in "available" state

### 2. Reserve Seats (Authenticated)
**Endpoint:** `POST /shows/{id}/reserve`
**Authentication:** Identity from auth token (NOT request body)
**Request:**
```json
{
  "seats": ["A12"],
  "idempotency_key": "..."
}
```
**Success Response (201):**
```json
{
  "reservation_id": "...",
  "show_id": "...",
  "user_id": "...",
  "seats": ["A12"],
  "amount_paise": 25000,
  "status": "confirmed"
}
```

**Required Behaviors:**
- **No double-sell:** A seat confirmed/held for one user can never be confirmed for another
- **Race condition:** Exactly one winner for hot seat contention; losers get 409 (not 500)
- **Per-user limit:** Max 4 seats per user per show (configurable, default 4)
- **Idempotency:** Same idempotency key reserves exactly once; retry returns original reservation
- **Idempotency conflict:** Same key with different body (different seats) → 409
- **Partial requests:** Define behavior (all-or-nothing vs best-effort) and make it hold under concurrency

### 3. Release/Expire Hold
**Options (choose one):**
- Explicit: `POST /reservations/{id}/cancel` (only owner may cancel)
- OR time-boxed hold that auto-expires and returns seat to available

**Requirements:**
- Released seat must become cleanly re-bookable
- Release must never resurrect a seat already confirmed to someone else

### 4. Show State
**Endpoint:** `GET /shows/{id}`
**Response:** Per-seat status (available/held/confirmed) and counts
**Invariant:** `available + held + confirmed == total_seats` must hold at all times

### 5. Health & Metrics
**Health Endpoints:**
- Liveness endpoint
- Readiness endpoint that checks DB dependency (fails closed when DB down)

**Metrics (Prometheus-style):**
- Reservations confirmed (counter)
- Reservations declined by reason (seat-taken / per-user-limit / idempotent-replay)
- Seats available (gauge)
- Must reconcile with API state

## Correctness Requirements (Critical - These Will Be Tested)

**Test Scenario:** ~20,000 concurrent reservations at fresh show, many targeting same hot seats, some retrying with same key

**Must satisfy ALL:**
1. **No double-sell:** For each hot seat stormed, exactly one 201; everyone else 409
2. **Zero 5xx:** No server errors across the entire burst (declines are 4xx)
3. **Reconciliation invariant:** `available + held + confirmed == total_seats` holds during and after burst
4. **Idempotent retries:** Same key = one reservation; different seats on same key = 409
5. **Per-user limit:** User firing 10 parallel reserves on limit=4 show ends with at most 4 held
6. **Identity enforcement:** Token-derived identity only; request body spoofing must not work

**Key Design Principle:**
- Atomic decision must be in single atomic step
- Read-then-write ("is A12 free? ok, take it") WILL double-sell under load
- Correct designs: conditional update guarded on state, unique constraint, row lock in deterministic order

## Deploy & Observe Requirements

### Deployment
- **Platform:** Render, Railway, Fly.io, or similar free tier
- **Public URL:** Must survive cold start and come up healthy
- **Containerization:** Dockerfile / docker-compose so clean checkout runs same way as deployed

### Health Checks
- **Liveness:** Basic health endpoint
- **Readiness:** Actually checks dependency (DB reachable), fails closed when dependency down

### Metrics
- **Format:** Prometheus-style
- **Minimum required:**
  - Reservations confirmed (counter)
  - Reservations declined by reason (seat-taken / per-user-limit / idempotent-replay)
  - Seats available (gauge)
- Must reconcile with API state and observable behavior

### Logging
- **Format:** Structured logs
- **Must include:** Correlation/request ID
- **Access:** Public log access if platform allows, or screen recording of live logs under load

### Burst Testing
- **One-command script:** `make burst` or `./burst.sh <BASE_URL>`
- **Must do:**
  - Reproduce on-sale stampede against live URL
  - Include hot-seat storm (many users, same seat)
  - Print outcome distribution (confirmed / declined-by-reason / 5xx)
  - Print final reconciliation

## Deliverables

1. **Public Git repo** with full commit history (commit incrementally)
2. **Live URL** of deployed service
3. **Burst script** and how to run it, in README
4. **Metrics + logs access**
5. **WRITEUP.md** with:
   - Atomic decision mechanism (exact mechanism and why race-free)
   - Multi-seat deadlock avoidance
   - Idempotency implementation (key storage, exactly-once enforcement, same-key-different-body handling)
   - Holds & expiry mechanism
   - Consistency vs availability under partition
   - Observability (what you'd get paged for at 2am)
   - AI usage (directed vs decided - be specific and honest)
   - What you'd do next

## Ground Rules

- **Money values:** Integer paise (minor units), never floats
- **AI tools:** Allowed and expected, disclose usage honestly
- **Clean checkout:** Must build and run from clone
- **Deployment:** Deploy that's down or build that fails = common failure point

## Technical Constraints

- **Language/framework:** Any
- **Datastore:** Single database is fine and encouraged
- **Authentication:** Token-based, identity from token NOT request body
- **Per-user limit:** Default 4, configurable
