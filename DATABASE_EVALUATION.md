# Database Evaluation for Seat Reservation Service (Java/Spring Boot)

## Options Considered

### 1. H2 Database (in-memory or file-based)
**Pros:**
- Full SQL with ACID guarantees
- Row-level locks (`SELECT FOR UPDATE`) for atomic operations
- Unique constraints for no-double-sell and idempotency
- Can run in-memory (`jdbc:h2:mem:`) or with file persistence (`jdbc:h2:file:`)
- Zero external dependencies (embedded)
- Excellent Spring Boot integration (auto-configuration)
- Simple deployment (no separate DB service)
- Excellent for single-process services
- Supports PostgreSQL compatibility mode

**Cons:**
- Single-process only (not distributed)
- Not suitable for horizontal scaling (but not required here)

**Concurrency Model (H2):**
- MVCC (Multi-Version Concurrency Control)
- Row-level locks via `SELECT FOR UPDATE`
- Serializable isolation for transactions
- Good concurrency for read-heavy workloads

**Atomic Operations (H2):**
- `INSERT` with unique constraint → fails if seat taken
- `SELECT FOR UPDATE` → locks rows for update
- Transactional all-or-nothing for multi-seat
- Spring Data JPA with `@Lock(LockModeType.PESSIMISTIC_WRITE)`

**Java Ecosystem Fit:**
- Native Spring Boot support
- Hibernate dialect included
- Easy configuration via `application.yml`
- Fast startup (critical for cold starts on cloud platforms)

### 2. SQLite (via xerial/sqlite-jdbc)
**Pros:**
- Full SQL with ACID guarantees
- Row-level locks (`SELECT FOR UPDATE`) for atomic operations
- Unique constraints for no-double-sell and idempotency
- WAL mode enables concurrent readers
- Can run in-memory or with file persistence
- Zero external dependencies (embedded)
- Simple deployment (no separate DB service)

**Cons:**
- Single-process only (not distributed)
- Requires more manual configuration in Spring Boot
- SQLite JDBC driver has limitations compared to H2
- Less natural Spring Boot integration

**Concurrency Model (SQLite):**
- WAL mode: Multiple readers, single writer
- Row-level locks prevent double-booking
- Serializable isolation for transactions

**Atomic Operations (SQLite):**
- `INSERT` with unique constraint → fails if seat taken
- `SELECT FOR UPDATE` → locks rows for update
- Transactional all-or-nothing for multi-seat

### 3. PostgreSQL
**Pros:**
- Full ACID, production-grade
- Excellent concurrency control
- Rich constraint system
- Native Spring Boot support
- Suitable for horizontal scaling

**Cons:**
- Requires external deployment (separate service)
- Overkill for single-process service
- More complex deployment (Docker compose needed)
- Free tier may have connection limits
- Network latency overhead
- Additional service to monitor and maintain

**Deployment Complexity:**
- Need to manage PostgreSQL instance separately
- Connection pooling configuration
- Network security considerations
- Not ideal for "clean checkout must build and run" requirement

### 4. Redis
**Pros:**
- Extremely fast
- Good for simple key-value operations
- Built-in TTL for hold expiry
- Spring Data Redis support

**Cons:**
- No native SQL constraints (must implement manually)
- Complex multi-seat operations require Lua scripts
- Idempotency requires careful key design
- No native row-level locking
- Would need external Redis deployment
- More complex to ensure ACID guarantees
- Not relational - harder to maintain data integrity

**Atomic Operations (Redis):**
- Lua scripts for atomic multi-seat
- SETNX for idempotency
- More fragile under edge cases
- Harder to verify correctness

### 5. Custom In-Memory (Java concurrent collections)
**Pros:**
- Maximum control
- Zero dependencies
- Fast

**Cons:**
- Must implement all ACID guarantees manually
- Easy to get wrong under high concurrency
- No built-in constraints
- Complex deadlock avoidance for multi-seat
- No persistence (data loss on crash)
- Hard to verify correctness
- No query capabilities

**Atomic Operations (Custom):**
- Manual synchronized blocks or ReentrantLock
- Complex state management
- High risk of race conditions
- Not recommended for production

## Recommendation: H2 Database (file-based)

**Why H2 is ideal for this Java/Spring Boot challenge:**

1. **Correctness First:** The entire challenge is about correctness under load. H2's ACID guarantees, row-level locks, and unique constraints provide the exact primitives needed:
   - Unique constraint on `(show_id, seat_number)` → no double-sell
   - Unique constraint on `idempotency_key` → exactly-once reservation
   - `SELECT FOR UPDATE` → atomic seat locking
   - Transactional all-or-nothing → multi-seat atomicity

2. **Spring Boot Integration:** H2 is the natural choice for Spring Boot applications:
   - Auto-configuration works out of the box
   - Hibernate dialect included
   - Actuator health checks automatically configured
   - No manual driver configuration needed
   - Seamless integration with Spring Data JPA

3. **Simplicity:** Single JAR deployment, no external DB service to deploy, monitor, or fail. Perfect for the "clean checkout must build and run" requirement.

4. **Performance:** In-memory H2 is extremely fast. File-based H2 with MVCC allows concurrent readers, which is what we need (many reads of show state, fewer writes for reservations).

5. **Deployment Simplicity:** No separate database container, no network latency, no connection pooling issues. Ideal for Render/Railway/Fly.io free tiers.

6. **PostgreSQL Compatibility Mode:** H2 can run in PostgreSQL compatibility mode, making it easy to switch to PostgreSQL later if horizontal scaling is needed:
   ```yaml
   spring:
     datasource:
       url: jdbc:h2:file:/app/data/seats.db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
   ```

7. **Cold Start Performance:** H2 starts up instantly, which is critical for cloud platforms that frequently cold-start services.

**Concurrency Strategy with H2:**
- Use MVCC (default in H2)
- Serializable isolation via Spring `@Transactional(isolation = Isolation.SERIALIZABLE)`
- For multi-seat requests: lock seats in deterministic order (sorted by seat_number) to prevent deadlocks
- Short transactions to minimize lock contention
- HikariCP connection pool (default in Spring Boot)

**Persistence Strategy:**
- Use file-based H2 (`jdbc:h2:file:/app/data/seats.db`) for durability
- Store file in persistent volume on cloud platforms
- Can switch to in-memory (`jdbc:h2:mem:seats`) for pure speed if persistence not needed

**Spring Boot Configuration:**
```yaml
spring:
  datasource:
    url: jdbc:h2:file:/app/data/seats.db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
    driver-class-name: org.h2.Driver
  h2:
    console:
      enabled: false
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
        format_sql: true
```

**Deploy Platform Fit:**
- Render/Railway/Fly.io: H2 file on persistent storage
- No external DB service cost
- Simple Dockerfile (just the JAR)
- Fast cold starts
- Health checks via Spring Boot Actuator

## Alternative: PostgreSQL (if multi-instance scaling needed)

If horizontal scaling becomes a requirement, PostgreSQL would be the choice. The migration from H2 to PostgreSQL is straightforward:
- Same Spring Data JPA abstractions
- Change datasource URL and driver
- Hibernate dialect change
- H2's PostgreSQL compatibility mode helps test locally

But for this challenge's single-instance constraint, H2 is superior in simplicity while providing identical correctness guarantees.

## Final Decision

**Use H2 Database in file-based mode with PostgreSQL compatibility.**
- Atomic operations via row-level locks and unique constraints
- All-or-nothing multi-seat via transactions with deterministic locking order
- Idempotency via unique constraint on idempotency_key
- Simple deployment, maximum correctness
- Native Spring Boot integration
- Fast cold starts (critical for cloud platforms)
- Easy migration path to PostgreSQL if needed
