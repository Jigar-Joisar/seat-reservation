# Seat Reservation Service - Design Write-up

## Atomic Decision Mechanism

### Exact Mechanism

The atomic decision for seat allocation lives in the database layer using **pessimistic locking with unique constraints**:

1. **Pessimistic Locking (`SELECT FOR UPDATE`):**
   - Implemented via Spring Data JPA's `@Lock(LockModeType.PESSIMISTIC_WRITE)`
   - Takes a row-level lock on seats before checking availability
   - Blocks other transactions attempting to lock the same rows
   - Ensures the "check-then-act" operation is atomic

   ```java
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Query("SELECT s FROM Seat s WHERE s.show.id = :showId AND s.seatNumber IN :seatNumbers")
   List<Seat> findByShowIdAndSeatNumberInWithLock(@Param("showId") String showId,
                                                  @Param("seatNumbers") List<String> seatNumbers);
   ```

2. **Unique Constraints:**
   - `(show_id, seat_number)` unique constraint in seats table prevents duplicate seat insertion
   - `idempotency_key` unique constraint in reservations table prevents duplicate reservations
   - Database enforces these constraints at the lowest level, making double-booking impossible

3. **Serializable Transactions:**
   - `@Transactional(isolation = Isolation.SERIALIZABLE)` ensures the highest isolation level
   - Prevents phantom reads and non-repeatable reads
   - The entire operation (check limit → lock seats → update → create reservation) is atomic

### Why It's Race-Free

This design is race-free because:

1. **Database-level locking:** The lock is taken at the database engine level, not in application memory. All concurrent processes see the same lock state.

2. **No read-then-write gap:** With `SELECT FOR UPDATE`, the read and write are part of the same locked operation. No other transaction can modify the locked rows between the read and write.

3. **Deterministic ordering:** Seats are sorted by `seat_number` before locking. This prevents deadlocks when multiple users request overlapping seat sets. If User A requests ["A3", "A1"] and User B requests ["A2", "A3"], both sort their requests: A locks A1 then A3, B locks A2 then blocks on A3. A completes, releases locks, B continues. No circular wait = no deadlock.

4. **Constraint as final guard:** Even if application logic has a bug, the unique constraint at the database level prevents double-booking. The INSERT will fail with a constraint violation.

5. **Single point of truth:** The database is the single source of truth. All decisions are made there, eliminating race conditions between application instances.

## Multi-Seat Deadlock Avoidance

### Mechanism

Multi-seat requests lock seats in **deterministic order** (sorted by `seat_number`):

```java
// Sort seats in deterministic order to prevent deadlocks
List<String> sortedSeats = request.getSeats().stream().sorted().toList();
```

### Why This Prevents Deadlocks

Deadlocks require four conditions (Coffman conditions):
1. Mutual exclusion
2. Hold and wait
3. No preemption
4. Circular wait

Our design eliminates **circular wait**:

**Without deterministic ordering:**
- User A requests ["A3", "A1"] → locks A3, then tries A1
- User B requests ["A1", "A3"] → locks A1, then tries A3
- Both are waiting for each other → deadlock

**With deterministic ordering:**
- User A requests ["A3", "A1"] → sorts to ["A1", "A3"] → locks A1, then A3
- User B requests ["A1", "A3"] → sorts to ["A1", "A3"] → tries A1, blocks
- User A completes, releases locks
- User B acquires A1, then A3
- No circular wait = no deadlock

### Additional Safeguards

1. **Short transactions:** Locks are held for minimal time (just the check and update)
2. **Retry on lock timeout:** H2/HikariCP handles lock contention gracefully
3. **Connection pool sizing:** Sufficient connections to handle concurrency without excessive queuing

## Idempotency Implementation

### Key Storage

Idempotency keys are stored in the `reservations` table:

```sql
CREATE TABLE reservations (
    ...
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    ...
);
```

The unique constraint ensures exactly-once insertion.

### Exactly-Once Enforcement

1. **First request:**
   - Check if idempotency_key exists → not found
   - Proceed with reservation
   - INSERT with idempotency_key → succeeds
   - Return reservation

2. **Concurrent retry:**
   - Check if idempotency_key exists → not found (race condition)
   - Proceed with reservation
   - INSERT with idempotency_key → fails with constraint violation
   - Catch `DataIntegrityViolationException`
   - Retry lookup → finds the winner's reservation
   - Return the winner's reservation

3. **Later retry:**
   - Check if idempotency_key exists → found
   - Validate body matches (seat list is the same)
   - Return existing reservation

### Same-Key-Different-Body Handling

If a request uses the same idempotency_key but with different seats:

```java
if (existing.isPresent()) {
    Reservation existingRes = existing.get();
    if (!seatListsMatch(requestSeats, existingRes.getSeats())) {
        throw new IdempotencyConflictException("Same idempotency key with different seats");
    }
    return existingRes;
}
```

- Response: 409 Conflict with error "idempotency_conflict"
- Reasoning: This protects against accidental key reuse or malicious attempts to override reservations
- The key is effectively "claimed" by the first successful reservation

### Why This Is Safe

1. **Database enforcement:** The unique constraint prevents two successful INSERTs with the same key
2. **Atomic check-and-insert:** The lookup and INSERT happen in the same transaction
3. **Explicit validation:** Body mismatch is detected and rejected explicitly
4. **Idempotent retry path:** Retries with the same body return the original reservation

## Holds & Expiry

### Current Implementation

The current implementation uses **time-boxed holds with background cleanup**:

1. **Hold creation:** When a reservation is created, seats are immediately marked as `CONFIRMED` (not `HELD`). The "hold" concept is simplified for this challenge.

2. **Hold expiry:** A scheduled task runs every 30 seconds to expire held seats:

```java
@Scheduled(fixedRate = 30000)
public void expireHolds() {
    Instant now = Instant.now();
    int expiredCount = seatRepository.expireHeldSeats(now);
}
```

```sql
UPDATE seats SET status = 'AVAILABLE', held_by_user_id = NULL, held_at = NULL, hold_expires_at = NULL
WHERE status = 'HELD' AND hold_expires_at < :now
```

### Race Condition Handling

If a hold is being confirmed while expiring:

1. **Confirmation wins:**
   - Confirmation transaction locks the row first (PESSIMISTIC_WRITE)
   - Expiry UPDATE waits for lock
   - Confirmation updates status to CONFIRMED
   - Expiry UPDATE finds status != 'HELD' → does nothing
   - Result: Seat is confirmed (correct)

2. **Expiry wins:**
   - Expiry UPDATE locks the row first
   - Confirmation waits for lock
   - Expiry updates status to AVAILABLE
   - Confirmation finds status != 'AVAILABLE' → fails with SeatNotAvailableException
   - Result: Seat is released, user must retry (correct)

The pessimistic lock ensures that only one operation can modify the seat at a time, making this race-free.

### Alternative: Explicit Cancel

The service also supports explicit cancellation via `POST /reservations/{id}/cancel`:

```java
@Transactional
public void cancelReservation(String reservationId, String userId) {
    Reservation reservation = reservationRepository.findById(reservationId)
        .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));

    if (!reservation.getUserId().equals(userId)) {
        throw new IllegalArgumentException("User can only cancel their own reservations");
    }

    reservation.setStatus(Reservation.ReservationStatus.CANCELLED);
    reservationRepository.save(reservation);
}
```

### Current Limitation

The current implementation marks the reservation as cancelled but does not release the seats back to available. In a production system, we would:
1. Track which seats belong to which reservation (via a junction table)
2. On cancellation, update those seats back to AVAILABLE
3. Handle race conditions with the same locking mechanism

## Consistency vs Availability Under Partition

### Design Choice: Strong Consistency

This service prioritizes **consistency over availability**:

- **CAP theorem choice:** CP (Consistency + Partition tolerance)
- **If the database is unreachable:** The readiness check fails (`/actuator/health` returns 503)
- **Behavior:** The service rejects requests rather than serve stale or incorrect data

### Implementation

1. **Readiness check with DB connectivity:**
   ```yaml
   management:
     health:
       db:
         enabled: true
   ```
   Spring Boot Actuator automatically checks DB connectivity. If the DB is down, the health check fails.

2. **Fail-closed approach:**
   - If DB is down, readiness endpoint returns 503
   - Load balancers (Render/Railway) stop routing traffic
   - Users get a clear error rather than incorrect data

3. **Transaction boundaries:**
   - All operations are transactional
   - If a transaction fails, it rolls back completely
   - No partial updates that could corrupt state

### Trade-offs

**Why this choice for this challenge:**

1. **Correctness is the primary requirement:** The challenge explicitly states "correctness under load" and "never sell the same seat twice"
2. **Data integrity is non-negotiable:** Selling a seat twice is worse than temporarily being unavailable
3. **Single-instance deployment:** The challenge allows a single database, so network partitions are less likely
4. **Reconciliation invariant:** The invariant `available + held + confirmed == total_seats` must hold at all times

**What we sacrifice:**

1. **Availability during DB outages:** The service is unavailable if the DB is down
2. **Performance under extreme load:** Strong consistency and serializable isolation have higher overhead
3. **Geographic distribution:** Single DB prevents multi-region deployment

**How to improve for production:**

1. **Add a cache layer:** Read-only cache for show state with TTL
2. **Implement circuit breakers:** Fail fast if DB is slow/down
3. **Use leader election:** If scaling to multiple instances, elect a leader for state management
4. **Consider eventual consistency:** For reads only, writes still strongly consistent

## Observability

### What to Page On at 2am

1. **5xx error rate > 1%**
   - **Why:** Indicates system failure, not normal declines
   - **Metric:** `http_server_requests_seconds_count{status=~"5.."}`
   - **Action:** Investigate logs, check DB connectivity, restart if needed

2. **DB connectivity failure**
   - **Why:** Service is unavailable, reservations failing
   - **Metric:** `/actuator/health` returning 503
   - **Action:** Check DB logs, verify disk space, restart DB if needed

3. **Hold expiry cleanup failures**
   - **Why:** Could lead to seat leak, users cannot book released seats
   - **Metric:** Log pattern "Hold expiry cleanup failed"
   - **Action:** Manual cleanup query, investigate DB locks

4. **Reconciliation invariant violation**
   - **Why:** Data corruption, critical correctness issue
   - **Metric:** Custom metric or alert on GET /shows/{id} response
   - **Action:** Immediate investigation, potentially restore from backup

5. **Reservation latency p99 > 5s**
   - **Why:** Performance degradation, user experience impact
   - **Metric:** `http_server_requests_seconds{uri="/shows/*/reserve", quantile="0.99"}`
   - **Action:** Check DB performance, increase connection pool, investigate locks

### Monitoring Dashboard

**Key visualizations:**

1. **Reservation rate over time:**
   - Confirmed reservations/min
   - Declined reservations/min (stacked by reason)
   - Shows demand patterns and contention levels

2. **Seat availability by show:**
   - Gauge showing available/held/confirmed per show
   - Shows seating progress and hot shows

3. **Error rate:**
   - 4xx vs 5xx over time
   - 5xx should always be near zero

4. **Database performance:**
   - Query latency (p50, p95, p99)
   - Connection pool utilization
   - Lock wait time

5. **System health:**
   - JVM memory usage
   - GC frequency
   - Thread pool utilization

### Alerting Strategy

**Critical alerts (page immediately):**
- 5xx error rate > 1%
- DB connectivity failure
- Reconciliation invariant violation

**Warning alerts (email within 5 min):**
- p99 latency > 2s
- Hold expiry cleanup failures
- Memory usage > 80%

**Info alerts (daily digest):**
- Reservation volume summary
- Peak concurrency
- Most declined seats (shows demand patterns)

## AI Usage

### Directed vs Decided

I used AI as a **directed tool**, not an autonomous decision-maker:

**What AI did (directed):**
1. **Architecture evaluation:** I asked AI to compare database options (SQLite vs Redis vs custom) and explain trade-offs. I made the final decision to use H2 based on Spring Boot integration and deployment simplicity.
2. **Code structure:** I asked AI to suggest Spring Boot project structure and best practices. I implemented the actual code with specific business logic.
3. **Documentation:** I asked AI to help format and structure the README and WRITEUP. I wrote the actual content based on my design decisions.
4. **Burst testing script:** I asked AI to generate a concurrent testing script template. I customized it for this specific API and added reconciliation validation.

**What I decided (human):**
1. **Database choice:** H2 over SQLite or PostgreSQL (based on Spring Boot integration, cold start performance, deployment simplicity)
2. **Concurrency model:** Pessimistic locking over optimistic locking (for stronger correctness guarantees)
3. **Isolation level:** SERIALIZABLE over READ_COMMITTED (for strongest correctness)
4. **Idempotency strategy:** Unique constraint over distributed cache (for simplicity and correctness)
5. **Hold expiry:** Background task over Redis TTL (for database-level consistency)
6. **Deterministic ordering:** Sort by seat_number (proven deadlock avoidance pattern)
7. **Consistency vs availability:** Strong consistency (CP over AP) based on challenge requirements

### Specific Examples

**AI-assisted:**
- "Generate a Spring Boot project structure for a REST API with JPA"
- "Compare SQLite and H2 for embedded database use"
- "Write a Python script for concurrent HTTP requests"

**Human-decided:**
- "Use H2 with PostgreSQL compatibility mode for easy migration"
- "Implement idempotency with unique constraint, not Redis"
- "Use SERIALIZABLE isolation for strongest correctness"
- "Prioritize consistency over availability (CP in CAP)"

### Why This Approach

1. **Interview transparency:** The challenge explicitly asks to "extend this service live in the interview." I need genuine understanding of the code, not just AI-generated code I can't explain.

2. **Learning value:** I learned more by making design decisions and understanding trade-offs than by letting AI decide everything.

3. **Ownership:** I can defend every design decision because I made them, not AI.

4. **Correctness:** For concurrency-critical systems, proven patterns (pessimistic locking, unique constraints) are safer than AI-suggested novel approaches.

## What I'd Do Next

### Immediate Improvements

1. **Fix seat release on cancellation:**
   - Add a `reservation_seats` junction table to track which seats belong to which reservation
   - On cancellation, update those specific seats back to AVAILABLE
   - Use the same locking mechanism for race condition safety

2. **Add proper hold mechanism:**
   - Implement actual HE → CONFIRMED transition (currently skip HE for simplicity)
   - Make hold duration configurable per show
   - Add hold confirmation endpoint (user converts hold to confirmed)

3. **Improve burst testing:**
   - Add realistic JWT token generation (call an auth endpoint)
   - Test with multiple shows simultaneously
   - Test with varying seat counts and price points
   - Add performance benchmarking (requests/sec)

### Production Readiness

4. **Add admin authentication:**
   - Implement proper admin role for show creation
   - Add JWT-based admin tokens
   - Secure the POST /shows endpoint

5. **Add rate limiting:**
   - Per-user rate limiting on reservation endpoints
   - Global rate limiting to prevent abuse
   - Circuit breakers for downstream dependencies

6. **Improve observability:**
   - Add distributed tracing (OpenTelemetry)
   - Add custom metrics for business logic (revenue, shows per day)
   - Add alerting integration (PagerDuty, Slack)

7. **Add caching:**
   - Cache show state reads (Redis or in-memory)
   - Invalidate cache on seat updates
   - Cache reservation lookups by idempotency key

### Scalability

8. **Horizontal scaling:**
   - Move to PostgreSQL for multi-instance support
   - Add connection pooling optimization
   - Consider read replicas for show state queries

9. **Multi-region deployment:**
   - Add leader election for show state management
   - Implement event sourcing for reservation events
   - Add conflict resolution for multi-region writes

10. **API improvements:**
    - Add GraphQL endpoint for flexible queries
    - Add WebSocket for real-time seat availability updates
    - Add webhook notifications for reservation events

### Testing

11. **Add comprehensive tests:**
    - Unit tests for all service methods
    - Integration tests for full API flows
    - Load tests with realistic traffic patterns
    - Chaos engineering (kill DB, test recovery)

12. **Add property-based testing:**
    - Test concurrency with random seat combinations
    - Test idempotency with random key reuse patterns
    - Verify reconciliation invariant under random operations

### Documentation

13. **Improve documentation:**
    - Add API documentation (OpenAPI/Swagger)
    - Add architecture decision records (ADRs)
    - Add runbooks for common issues
    - Add performance tuning guide

## Conclusion

This service demonstrates that correctness under high concurrency is achievable with:
- Database-level atomic operations (pessimistic locking, unique constraints)
- Deterministic ordering to prevent deadlocks
- Strong consistency over availability
- Comprehensive observability

The design prioritizes the challenge's core requirement: "never sell the same seat twice" over performance or scalability. With this foundation, the service can be extended and optimized for production use while maintaining its correctness guarantees.
