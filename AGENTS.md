# AGENTS.md — Seat Reservation Service

> **Purpose:** Authoritative working reference for AI agents and developers modifying this
> codebase. Every behavioral claim in this file was **verified against the running service**
> (see "Verification status"). Where this file disagrees with `README.md` / `ARCHITECTURE.md`,
> **this file is correct** — those docs describe the intended design, this describes the code
> as it actually exists.

---

## 1. What this project is

A take-home/interview-style challenge: a **high-concurrency seat reservation HTTP API** that
must survive an "on-sale stampede" (~20k concurrent requests) without ever double-selling a
seat. Single-instance service, single embedded database.

Core guarantees the design aims for (see §8 for which actually hold):

- No double-sell under concurrency (losers get 409, never 500)
- Idempotent retries via client-supplied `idempotencyKey`
- Per-user seat limit per show (default 4)
- Reconciliation invariant: `available + held + confirmed == total` seats
- Identity comes **only** from the JWT — never from the request body

## 2. Tech stack

| Layer | Choice | Version |
|---|---|---|
| Language | Java | 17 (pom targets 17; JDK 24 installed locally works) |
| Framework | Spring Boot | 3.2.0 |
| Build | Maven | 3.6+ (`mvn` at `/opt/homebrew/bin/mvn`) |
| DB | H2 file-based, PostgreSQL-compat mode | runtime dep, file at `./data/seats.db` |
| ORM | Spring Data JPA / Hibernate | `ddl-auto: update` |
| Auth | JWT HS256 via `jjwt` | 0.11.5 |
| Metrics | Micrometer + Prometheus registry | via Actuator |
| Logging | Logback + logstash-logback-encoder (JSON) | 7.4 |
| Tests | **None exist** — `src/test/` is absent | spring-boot-starter-test is on the classpath |

Not a git repository (no `.git`). `target/seat-reservation-1.0.0.jar` is a prebuilt artifact.

## 3. Build / run / verify

```bash
# Build (skip tests — there are none)
mvn clean package -DskipTests        # → target/seat-reservation-1.0.0.jar

# Run (choose one)
mvn spring-boot:run
java -jar target/seat-reservation-1.0.0.jar
docker compose up --build            # Dockerfile = multi-stage maven → temurin-17-jre-alpine

# Smoke check
curl localhost:8080/health/live      # → 200 "OK"
curl localhost:8080/actuator/health  # → {"status":"UP", components:{db:UP,...}}
```

- Port: **8080**. DB file: `./data/seats.db` relative to CWD (delete `data/` to reset state).
- H2 console disabled. `application-prod.yml`, `data.sql`, `MetricsConfig` are referenced in
  ARCHITECTURE.md but **do not exist**.
- Verification ritual after any change: rebuild jar → run → exercise the curl flows in §5.

## 4. Repository map

```
pom.xml                        Spring Boot 3.2.0 parent; deps: web, security, data-jpa, h2,
                               actuator, micrometer-prometheus, jjwt 0.11.5, logstash-encoder,
                               validation, test
Dockerfile                     two-stage: maven:3.9-eclipse-temurin-17 → temurin:17-jre-alpine
docker-compose.yml             port 8080, ./data volume, env vars, wget healthcheck
burst.py / burst.sh            concurrent load scripts — CURRENTLY BROKEN, see §9
src/main/resources/
  application.yml              all config (see §7); NO jackson naming-strategy set
  logback-spring.xml           JSON stdout logging (logstash encoder)
src/main/java/com/seatreservation/
  SeatReservationApplication   @SpringBootApplication + @EnableScheduling
  config/
    SecurityConfig             stateless, CSRF off; permits /health/**, /actuator/**,
                               /shows, /shows/{id}; everything else authenticated;
                               installs JwtAuthenticationFilter before UsernamePasswordAuthFilter
    JwtConfig                  HS256 sign/verify; sub claim = userId; 24h expiry
  filter/                      (all are OncePerRequestFilter @Components — order is undefined
    RequestIdFilter            generates X-Request-ID, puts `request_id` in MDC, echoes header)
    LoggingFilter              logs "Request: M P" and "Response: M P - Status: N (Duration)"
    JwtAuthenticationFilter    skips /health & /actuator; validates Bearer JWT, sets
                               SecurityContext principal = userId String (no authorities)
  controller/
    ShowController             POST /shows (open!), GET /shows/{id} (open)
    ReservationController      POST /shows/{showId}/reserve, POST /reservations/{id}/cancel
    HealthController           /health/live, /health/ready (ready does NOT check DB — see §8)
  service/
    ShowService                createShow (seats cascade), getShow + counts
    ReservationService         reserveSeats — THE critical path (see §6); cancelReservation
    HoldExpiryScheduler        @Scheduled(30s) bulk-expires HELD seats — dead code today (§8)
  repository/
    SeatRepository             findByShowIdAndSeatNumberInWithLock (@Lock PESSIMISTIC_WRITE),
                               expireHeldSeats (@Modifying bulk UPDATE), countByShowIdAndStatus
    ReservationRepository      findByIdempotencyKey, countByShowIdAndUserIdAndStatus
                               (counts CONFIRMED *reservations*, not seats — see §8)
    ShowRepository             plain JpaRepository
  model/
    Show                       id UUID-String, name, pricePaise, perUserLimit=4, seats 1:N
    Seat                       show FK, seatNumber, status enum (AVAILABLE|HELD|CONFIRMED),
                               held*/confirmed*/reservationId/holdExpiresAt columns;
                               UNIQUE(show_id, seat_number)
    Reservation                show FK, userId, seats (JSON string col), amountPaise,
                               status (CONFIRMED|CANCELLED), idempotencyKey UNIQUE
  model/dto/                   plain POJOs w/ getters/setters; @NotBlank/@NotEmpty/@Positive;
    CreateShowRequest          name, seats, pricePaise          ← binds camelCase ONLY
    ReserveSeatsRequest        seats, idempotencyKey            ← binds camelCase ONLY
    ShowResponse               id,name,pricePaise,perUserLimit,seats[{seatNumber,status}],counts
    ReservationResponse        reservationId,showId,userId,seats,amountPaise,status
  exception/
    GlobalExceptionHandler     @RestControllerAdvice → JSON {error,message,details?}
    SeatNotAvailableException      → 409 seat_not_available {seat, current_status}
    PerUserLimitExceededException  → 409 per_user_limit_exceeded {limit,current_count,requested_count}
    IdempotencyConflictException   → 409 idempotency_conflict
    (everything else: IllegalArgumentException → 400 invalid_request;
     ANY other exception incl. validation failures → 500 internal_error — see §8)
```

## 5. API — actual verified contract

**JSON is camelCase.** There is no Jackson naming strategy configured and no `@JsonProperty`
annotations. The snake_case payloads shown in README.md/burst scripts **do not bind** —
`price_paise`/`idempotency_key` arrive as `null` → constraint/validation failure → 500/400.
Enum statuses serialize UPPERCASE (`"AVAILABLE"`, `"CONFIRMED"`), not lowercase.

### Mint a test JWT (no issuance endpoint exists)

```bash
python3 -c "
import hmac,hashlib,base64,json,time
secret=b'your-secret-key-change-in-production'   # default JWT_SECRET
b64=lambda d: base64.urlsafe_b64encode(d).rstrip(b'=').decode()
h=b64(json.dumps({'alg':'HS256','typ':'JWT'}).encode())
n=int(time.time()); p=b64(json.dumps({'sub':'user-1','iat':n,'exp':n+86400}).encode())
print(f'{h}.{p}.{b64(hmac.new(secret,f\"{h}.{p}\".encode(),hashlib.sha256).digest())}')"
```

### Endpoints

| Method & path | Auth | Body | Verified response |
|---|---|---|---|
| `POST /shows` | none | `{name, seats[], pricePaise}` | 201 → `{id,name,pricePaise,perUserLimit,seats:[{seatNumber,status}],counts:{available,held,confirmed,total}}` |
| `GET /shows/{id}` | none | — | 200 same shape; unknown id → **400** `invalid_request` (not 404) |
| `POST /shows/{id}/reserve` | Bearer JWT | `{seats:[], idempotencyKey}` | 201 → `{reservationId,showId,userId,seats,amountPaise,status:"CONFIRMED"}` |
| `POST /reservations/{id}/cancel` | Bearer JWT | — | 204; only owner; already-cancelled → 400 |
| `GET /health/live` `/health/ready` | none | — | 200 `"OK"` (ready is unconditional — see §8) |
| `GET /actuator/health` `/actuator/health/{liveness,readiness}` `/actuator/prometheus` `/actuator/metrics` | none | — | db health detail included (`show-details: always`) |

Verified behaviors (reserve-test run):

- No `Authorization` header → **403** (not 401). Garbage Bearer token → **403**.
- Valid JWT + `{seats:["A1"],idempotencyKey:"k"}` → 201; replay same body+key → **201 with same
  `reservationId`** (replay also returns 201, not 200).
- Same key + different seats → 409 `{"error":"idempotency_conflict"}`.
- Taken seat → 409 `{"error":"seat_not_available","details":{"seat":"A1","current_status":"CONFIRMED"}}`.
- Over limit → 409 `{"error":"per_user_limit_exceeded","details":{"limit","current_count","requested_count"}}`.
- Bean-validation failure (missing `seats`, blank `idempotencyKey`) → **500 `internal_error`**
  (no `MethodArgumentNotValidException` handler exists).
- Missing `pricePaise` on show create → 500 (null → DB not-null violation).

## 6. The critical path — `ReservationService.reserveSeats`

Single `@Transactional(isolation = SERIALIZABLE)` method; order of operations:

1. `findByIdempotencyKey` — hit → compare seat lists (order-insensitive `containsAll`) →
   mismatch = `IdempotencyConflictException` (409); match = return existing reservation (replay).
2. `showRepository.findById` — 400 if absent.
3. Sort requested seat numbers ascending → **deterministic lock order** (deadlock avoidance).
4. `countByShowIdAndUserIdAndStatus(showId, userId)` — counts **CONFIRMED reservations**;
   `count + seats.size() > perUserLimit` → 409 `per_user_limit_exceeded`.
5. `findByShowIdAndSeatNumberInWithLock` — `SELECT ... FOR UPDATE` row locks on all requested
   seats. Size mismatch vs request → 400 "One or more seats not found".
6. Any seat `status != AVAILABLE` → 409 `seat_not_available`.
7. Set every seat `CONFIRMED` + `confirmedByUserId` + `confirmedAt`; `saveAll`.
   **`reservationId`, `heldAt`, `holdExpiresAt` are never set on seats.**
8. Serialize request seats to JSON string → build Reservation → `reservationRepository.save`.
   `amountPaise = pricePaise × seats.size()`. On `DataIntegrityViolationException`
   (idempotency race loser) → re-lookup key → return winner or 409 on mismatch.
9. `finally` → `reservation_duration_seconds` timer. Counters: `reservations_confirmed_total`,
   `reservations_declined_total{reason=seat_taken|seat_not_found|per_user_limit|idempotent_replay|idempotency_conflict}`,
   `reservations_cancelled_total`.

`cancelReservation`: owner-check (`userId` equality) → set status CANCELLED. **Seats are NOT
released** — they stay `CONFIRMED` forever (acknowledged in code comments + WRITEUP).

## 7. Configuration

| Property (application.yml) | Env var | Default |
|---|---|---|
| `server.port` | `SERVER_PORT` | 8080 |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/seats.db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE` |
| `app.jwt.secret` | `JWT_SECRET` | `your-secret-key-change-in-production` (weak default — override) |
| `app.jwt.expiration` | — | 86400000 ms (24h) |
| `app.reservation.per-user-limit` | `PER_USER_LIMIT` | 4 |
| `app.reservation.hold-duration-minutes` | `HOLD_DURATION_MINUTES` | 5 (currently unused — no holds) |
| `spring.jpa.hibernate.ddl-auto` | — | `update` (schema auto-created/migrated) |

HikariCP: defaults (pool size 10). Actuator exposed: `health,metrics,prometheus`; probes on.

## 8. Known bugs, gaps & doc-vs-code divergences (VERIFIED)

Fix-oriented agents should treat this as the backlog, ordered by impact:

1. **Bean-validation failures → 500.** `GlobalExceptionHandler` lacks a
   `MethodArgumentNotValidException` handler → falls into generic `Exception` →
   `500 internal_error`. Breaks the "zero 5xx" requirement; any malformed body (missing
   `seats`, blank `idempotencyKey`, non-binding snake_case fields) is a 500.
2. **API contract mismatch.** Code binds/returns camelCase; README, REQUIREMENTS, and both
   burst scripts use snake_case (`price_paise`, `idempotency_key`) and lowercase statuses.
   Decide on one convention — add `spring.jackson.property-naming-strategy: SNAKE_CASE`
   (and update DTOs if needed) or rewrite the docs/scripts.
3. **Per-user limit counts reservations, not seats.** Verified: one user ended with **7
   confirmed seats at limit=4** via three 2-seat + one 1-seat requests (4 reservations ≤ 4
   passes). Fix: sum seat counts from the `seats` JSON column (or better, a
   `reservation_seats` junction table) rather than `COUNT(reservations)`.
4. **Cancel doesn't release seats.** Reservation → CANCELLED but seat rows stay `CONFIRMED`;
   seats are never re-bookable. Also `Seat.reservationId` is never populated, so there's no
   seat→reservation back-reference; add the junction table noted in the code comment.
5. **HELD status & hold machinery is dead code.** Reserve goes straight to CONFIRMED;
   `heldByUserId/heldAt/holdExpiresAt` never written; `HoldExpiryScheduler` runs every 30 s
   but can never match a row; `HOLD_DURATION_MINUTES` env var is inert.
6. **Burst scripts cannot work as written.** They send `Authorization: Bearer user-N` (not a
   JWT → **403** for every request) and snake_case bodies (→ 500 at show-creation, before any
   reserve call). A 403/400 response is also not counted by either script, so results
   silently undercount. To fix: mint real HS256 JWTs (see §5) and use camelCase keys.
7. **Status-code deviations from docs:** unauthenticated → 403 (docs say 401); show not found
   → 400 (docs imply 404); cancel of another user's reservation → 400 (docs imply 403).
8. **`/health/ready` is unconditional** — returns 200 regardless of DB (comment defers to
   actuator). Real readiness probe: `GET /actuator/health/readiness` (checks db component).
   Requirement says readiness must fail closed — point probes there or fix the controller.
9. **`GlobalExceptionHandler.handleGenericException` doesn't log.** 500s produce a generic
   body with no stack trace in the JSON logs — add `log.error` there or debugging is blind.
10. **Idempotency keys are globally unique**, not scoped per user/show. Reusing a key from a
    *different show* returns the old reservation (seat lists compared across shows). Likely
    should validate `showId` on replay too.
11. **`POST /shows` is unauthenticated** (`.requestMatchers("/shows").permitAll()`) —
    intentional per comment ("in production, use admin auth") but any client can create shows.
12. **No test suite** (`src/test` missing) despite `mvn test`/`mvn verify` documented.
13. **Missing pieces promised in docs:** `seats_available` gauge and other seat gauges
    (ARCHITECTURE.md lists them; only counters + one timer exist), `MetricsConfig` class,
    `application-prod.yml`, `data.sql`, HikariCP tuning block.
14. Minor: `docker-compose.yml` has obsolete `version:` key; idempotent replay returns 201
    (HTTP-correct would arguably be 200/201 — consistent, just undocumented); duplicate seat
    numbers in one request → 400 "seats not found" (IN-clause dedupes).

## 9. Testing changes (agents)

There is no automated suite — verify manually:

```bash
mvn clean package -DskipTests && java -jar target/seat-reservation-1.0.0.jar &
# then run the §5 flows; for concurrency, write a small script or fix burst.py first:
#   - generate one HS256 JWT per user (sub=user-N)
#   - send {"seats":[...], "idempotencyKey": "..."} camelCase
#   - create shows with {"name","seats","pricePaise"}
```

After your change, re-verify at minimum: reserve happy-path 201, idempotent replay,
key-mismatch 409, seat-taken 409, limit 409, invalid-body status code, `counts` invariant
(`available+held+confirmed==total`), and `/actuator/prometheus` counters increment.

## 10. Conventions to follow

- Plain JPA entities + getters/setters — **no Lombok** (explicitly excluded in pom config).
- Field naming camelCase everywhere; DB columns snake_case via `@Column(name=...)`.
- Errors: throw a typed `RuntimeException` in `exception/` and map it in
  `GlobalExceptionHandler` to `{error, message, details?}` JSON — add new handlers rather than
  leaking to the generic 500.
- Business decisions belong in `@Service` + `@Transactional` methods; controllers stay thin
  (`authentication.getName()` → service → ResponseEntity).
- Metrics via injected `MeterRegistry` counters/timers at the service layer.
- Money: integer `pricePaise`/`amountPaise` (paise = minor units), never floats.
- New dependencies: prefer versions ≥7 days old; add via pom.xml.
- Docs-are-code: if you change behavior, update **this file**, README.md, and WRITEUP.md —
  they already diverge once and that caused real bugs (burst scripts).

## 11. Other docs in repo (and their accuracy)

| File | What it is | Trust level |
|---|---|---|
| `README.md` | user-facing API + deploy guide | **field names/statuses wrong** (snake_case claims); flows accurate |
| `REQUIREMENTS.md` | the original challenge spec | authoritative for *intent* |
| `ARCHITECTURE.md` | aspirational design doc | describes classes/files that don't exist; design rationale still valid |
| `DATABASE_EVALUATION.md` | why H2 over SQLite/PG/Redis | accurate rationale |
| `WRITEUP.md` | design decisions deep-dive | accurate about *mechanisms*; admits cancel/hold limitations |
| `BUILD_INSTRUCTIONS.md` | setup notes | stale — Maven IS now installed |
| `AGENTS.md` | this file | verified against running service (2026-10-02) |
