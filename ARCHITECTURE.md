# Seat Reservation Service - Architecture Design

## Tech Stack

- **Language:** Java 17+
- **Framework:** Spring Boot 3.2+
- **Build Tool:** Maven
- **Database:** H2 (in-memory) or SQLite (file-based) - both support ACID
- **ORM:** Spring Data JPA with Hibernate
- **Authentication:** JWT tokens via `jjwt` library
- **Metrics:** Spring Boot Actuator + Micrometer Prometheus
- **Logging:** Logback with structured JSON logging
- **Configuration:** Spring Boot configuration (application.yml + env vars)

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                Spring Boot Application                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  /shows      │  │  /reserve    │  │  /health     │  │
│  │  (admin)     │  │  (auth)      │  │  /metrics    │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                  Spring Filters/Interceptors            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  JWT Filter  │  │  Request ID  │  │  Logging     │  │
│  │              │  │  Filter      │  │  Filter      │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│              Spring REST Controllers                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  Show        │  │  Reservation  │  │  Health      │  │
│  │  Controller  │  │  Controller  │  │  Controller  │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                  Service Layer (@Service)               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  Show        │  │  Reservation  │  │  Metrics      │  │
│  │  Service     │  │  Service     │  │  Service     │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│              Spring Data JPA Repositories               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  Show        │  │  Seat        │  │  Reservation  │  │
│  │  Repository  │  │  Repository  │  │  Repository  │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                  H2 Database / SQLite                    │
│  - Unique constraints for atomicity                      │
│  - Pessimistic locking (@Lock)                           │
│  - Transactional (@Transactional)                        │
└─────────────────────────────────────────────────────────┘
```

## Database Schema

### Shows Table
```sql
CREATE TABLE shows (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price_paise BIGINT NOT NULL,
    per_user_limit INTEGER NOT NULL DEFAULT 4,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

### Seats Table
```sql
CREATE TABLE seats (
    id VARCHAR(36) PRIMARY KEY,
    show_id VARCHAR(36) NOT NULL,
    seat_number VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK(status IN ('available', 'held', 'confirmed')),
    held_by_user_id VARCHAR(36),
    held_at TIMESTAMP,
    confirmed_by_user_id VARCHAR(36),
    confirmed_at TIMESTAMP,
    reservation_id VARCHAR(36),
    hold_expires_at TIMESTAMP,
    FOREIGN KEY (show_id) REFERENCES shows(id) ON DELETE CASCADE,
    UNIQUE(show_id, seat_number)  -- Prevents duplicate seats in show
);

-- Index for fast seat lookups by status
CREATE INDEX idx_seats_show_status ON seats(show_id, status);
-- Index for hold expiry cleanup
CREATE INDEX idx_seats_hold_expiry ON seats(hold_expires_at) WHERE hold_expires_at IS NOT NULL;
```

### Reservations Table
```sql
CREATE TABLE reservations (
    id VARCHAR(36) PRIMARY KEY,
    show_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    seats TEXT NOT NULL,  -- JSON array: ["A1", "A2"]
    amount_paise BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK(status IN ('confirmed', 'cancelled')),
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,  -- Ensures exactly-once
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (show_id) REFERENCES shows(id) ON DELETE CASCADE
);

-- Index for user reservations
CREATE INDEX idx_reservations_user_show ON reservations(user_id, show_id, status);
-- Index for idempotency lookups
CREATE INDEX idx_reservations_idempotency ON reservations(idempotency_key);
```

## Atomic Operations Design

### 1. No Double-Sell Mechanism
**Mechanism:** Unique constraint on `(show_id, seat_number)` in seats table + pessimistic locking

**Flow:**
```java
@Transactional(isolation = Isolation.SERIALIZABLE)
public Reservation reserveSeats(String showId, List<String> seatNumbers, String userId, String idempotencyKey) {
    // Sort seats in deterministic order to prevent deadlocks
    List<String> sortedSeats = seatNumbers.stream().sorted().toList();

    // Lock seats using pessimistic locking
    List<Seat> seats = seatRepository.findByShowIdAndSeatNumberInWithLock(showId, sortedSeats);

    // Check if all seats are available
    for (Seat seat : seats) {
        if (!"available".equals(seat.getStatus())) {
            throw new SeatNotAvailableException(seat.getSeatNumber(), seat.getStatus());
        }
    }

    // Update all seats atomically
    for (Seat seat : seats) {
        seat.setStatus("confirmed");
        seat.setConfirmedByUserId(userId);
        seat.setConfirmedAt(Instant.now());
        seat.setReservationId(reservationId);
    }
    seatRepository.saveAll(seats);

    return reservation;
}
```

**Repository method with locking:**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM Seat s WHERE s.showId = :showId AND s.seatNumber IN :seatNumbers")
List<Seat> findByShowIdAndSeatNumberInWithLock(@Param("showId") String showId,
                                               @Param("seatNumbers") List<String> seatNumbers);
```

**Why it's race-free:**
- `PESSIMISTIC_WRITE` lock (`SELECT FOR UPDATE`) takes a row-level lock that blocks other transactions
- Deterministic ordering prevents deadlocks
- Unique constraint ensures no duplicate seat_number per show_id
- `@Transactional` with SERIALIZABLE isolation ensures all-or-nothing

### 2. Idempotency Mechanism
**Mechanism:** Unique constraint on `idempotency_key` in reservations table

**Flow:**
```java
@Transactional
public Reservation reserveSeats(String showId, List<String> seatNumbers, String userId, String idempotencyKey) {
    // First, check if idempotency_key exists
    Optional<Reservation> existing = reservationRepository.findByIdempotencyKey(idempotencyKey);

    if (existing.isPresent()) {
        Reservation existingRes = existing.get();
        // Validate body matches
        if (!seatListsMatch(seatNumbers, existingRes.getSeats())) {
            throw new IdempotencyConflictException("Same idempotency key with different seats");
        }
        // Return existing reservation
        return existingRes;
    }

    // Proceed with reservation creation
    // INSERT will fail if key was inserted by another transaction
    try {
        Reservation reservation = new Reservation(...);
        return reservationRepository.save(reservation);
    } catch (DataIntegrityViolationException e) {
        // Unique constraint violation - another transaction won
        // Retry the lookup
        Optional<Reservation> winner = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (winner.isPresent()) {
            if (!seatListsMatch(seatNumbers, winner.get().getSeats())) {
                throw new IdempotencyConflictException("Same idempotency key with different seats");
            }
            return winner.get();
        }
        throw e;
    }
}
```

**Why it's race-free:**
- Unique constraint at DB level prevents duplicate keys
- If two concurrent requests use same key, exactly one INSERT succeeds
- Loser catches `DataIntegrityViolationException`, retries lookup, and returns winner's reservation
- Body mismatch validation prevents misuse

### 3. Per-User Limit Mechanism
**Mechanism:** Count existing confirmed reservations before creating new one

**Flow:**
```java
@Transactional
public Reservation reserveSeats(String showId, List<String> seatNumbers, String userId, String idempotencyKey) {
    // In the same transaction as seat locking
    long currentHeldCount = reservationRepository.countByShowIdAndUserIdAndStatus(showId, userId, "confirmed");

    if (currentHeldCount + seatNumbers.size() > perUserLimit) {
        throw new PerUserLimitExceededException(perUserLimit, currentHeldCount, seatNumbers.size());
    }

    // Proceed with reservation
    // ...
}
```

**Repository method:**
```java
@Query("SELECT COUNT(r) FROM Reservation r WHERE r.showId = :showId AND r.userId = :userId AND r.status = :status")
long countByShowIdAndUserIdAndStatus(@Param("showId") String showId,
                                     @Param("userId") String userId,
                                     @Param("status") String status);
```

**Why it's race-free:**
- Count happens within the same transaction that locks seats
- Pessimistic locks on seats prevent concurrent modifications
- SERIALIZABLE isolation ensures consistent snapshot

### 4. Multi-Seat Deadlock Avoidance
**Mechanism:** Lock seats in deterministic order (sorted by seat_number)

**Why this works:**
- If User A requests ["A3", "A1"] and User B requests ["A2", "A3"]
- Both sort: A gets ["A1", "A3"], B gets ["A2", "A3"]
- A locks A1, then A3; B locks A2, then blocks on A3
- A completes, releases locks; B continues
- No circular wait condition = no deadlock

### 5. Hold Expiry Mechanism
**Mechanism:** Scheduled task that periodically expires holds

**Flow:**
```java
@Component
public class HoldExpiryScheduler {

    @Scheduled(fixedRate = 30000) // Every 30 seconds
    @Transactional
    public void expireHolds() {
        Instant now = Instant.now();
        int expiredCount = seatRepository.expireHeldSeats(now);
        log.info("Expired {} held seats", expiredCount);
    }
}
```

**Repository method:**
```java
@Modifying
@Query("UPDATE Seat s SET s.status = 'available', s.heldByUserId = NULL, s.heldAt = NULL, s.holdExpiresAt = NULL WHERE s.status = 'held' AND s.holdExpiresAt < :now")
int expireHeldSeats(@Param("now") Instant now);
```

**Race condition handling:**
- If a hold is being confirmed while expiring:
  - Confirmation transaction locks the row first (PESSIMISTIC_WRITE)
  - Expiry update will wait for lock
  - If confirmed first, expiry update finds status != 'held' and does nothing
  - If expired first, confirmation finds status != 'available' and fails

## API Endpoints

### POST /shows (admin)
- Creates a new show with seats
- All seats start in "available" status
- Returns show with ID

### POST /shows/{id}/reserve (authenticated)
- Requires valid JWT token
- Token determines user_id (not request body)
- Atomic seat reservation with all constraints
- Returns 201 on success, 409 on decline

### POST /reservations/{id}/cancel (authenticated)
- Only reservation owner can cancel
- Releases seats back to available
- Updates reservation status to "cancelled"

### GET /shows/{id}
- Returns per-seat status
- Returns counts (available, held, confirmed)
- Validates reconciliation invariant

### GET /health/live
- Simple liveness check
- Returns 200 if service is running

### GET /health/ready
- Checks DB connectivity
- Returns 200 if DB is reachable, 503 if not

### GET /metrics
- Prometheus metrics endpoint
- Exposes counters and gauges

## Metrics

### Counters (Micrometer Counter)
- `reservations_confirmed_total` - Total successful reservations
- `reservations_declined_total{reason="seat_taken"}` - Declined due to seat unavailable
- `reservations_declined_total{reason="per_user_limit"}` - Declined due to user limit
- `reservations_declined_total{reason="idempotent_replay"}` - Declined due to idempotency key reuse with different body
- `reservations_cancelled_total` - Total cancellations

### Gauges (Micrometer Gauge)
- `seats_available{show_id}` - Current available seats per show
- `seats_held{show_id}` - Current held seats per show
- `seats_confirmed{show_id}` - Current confirmed seats per show
- `active_reservations{show_id}` - Active reservations per show

### Timers (Micrometer Timer)
- `reservation_duration_seconds` - Time to process reservation
- `db_query_duration_seconds` - Database query duration

**Implementation:**
```java
@Autowired
private MeterRegistry meterRegistry;

public Reservation reserveSeats(...) {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        Reservation result = doReserve(...);
        meterRegistry.counter("reservations_confirmed_total").increment();
        return result;
    } catch (SeatNotAvailableException e) {
        meterRegistry.counter("reservations_declined_total", "reason", "seat_taken").increment();
        throw e;
    } catch (PerUserLimitExceededException e) {
        meterRegistry.counter("reservations_declined_total", "reason", "per_user_limit").increment();
        throw e;
    } finally {
        sample.stop(meterRegistry.timer("reservation_duration_seconds"));
    }
}
```

## Logging

### Format
- Structured JSON logging via Logback with JSON encoder
- Includes: timestamp, level, request_id, user_id, show_id, operation, duration, error

### Logback Configuration (logback-spring.xml)
```xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeContext>true</includeContext>
            <includeMdc>true</includeMdc>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="JSON" />
    </root>
</configuration>
```

### Request ID
- Generated in filter if not present in header
- Stored in MDC (Mapped Diagnostic Context)
- Included in all logs for a request via MDC

### Key Log Events
- Reservation request received
- Seat lock acquisition
- Idempotency check
- Per-user limit check
- Reservation confirmed/declined
- Database errors
- Hold expiry cleanup

## Deployment Architecture

### Dockerfile
- Multi-stage build
- Stage 1: Maven build
- Stage 2: Minimal JRE image (eclipse-temurin:17-jre-alpine)
- H2/SQLite file stored in persistent volume

```dockerfile
# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Docker Compose (local)
```yaml
services:
  app:
    build: .
    ports:
      - "8080:8080"
    volumes:
      - ./data:/app/data  # Database persistence
    environment:
      - SPRING_DATASOURCE_URL=jdbc:h2:file:/app/data/seats.db
      - JWT_SECRET=your-secret
      - PER_USER_LIMIT=4
      - HOLD_DURATION_MINUTES=5
      - SPRING_PROFILES_ACTIVE=prod
```

### Cloud Deployment (Render/Railway/Fly.io)
- Single service deployment
- Database file on persistent storage
- Environment variables for configuration
- Health checks configured
- No external database needed

## Concurrency Model

### H2/SQLite Configuration (application.yml)
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
        order_inserts: true
        order_updates: true
        jdbc:
          batch_size: 20
```

### Connection Pool (HikariCP - default in Spring Boot)
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 25
      minimum-idle: 5
      connection-timeout: 5000
```

### Transaction Strategy
- `@Transactional` with SERIALIZABLE isolation
- Short transactions (minimize lock time)
- All-or-nothing for multi-seat
- Immediate rollback on any exception
- Retry on `PessimisticLockingFailureException` (rare)

## Error Handling

### HTTP Status Codes
- 200: Success (GET)
- 201: Created (POST /shows, POST /reserve)
- 400: Bad request (invalid input)
- 401: Unauthorized (missing/invalid token)
- 403: Forbidden (not owner for cancel)
- 409: Conflict (seat taken, over limit, idempotency conflict)
- 404: Not found (show/reservation not found)
- 500: Internal server error (unexpected errors - should never happen in burst test)
- 503: Service unavailable (DB down - readiness check)

### Error Response Format
```json
{
  "error": "seat_already_taken",
  "message": "Seat A12 is not available",
  "details": {
    "seat": "A12",
    "current_status": "confirmed"
  }
}
```

## Security Considerations

### JWT Authentication
- HS256 signing using `jjwt` library
- Token contains: user_id, exp, iat
- Spring Security filter validates token and extracts user_id
- Request body user_id is ignored (token is source of truth)

**JWT Filter:**
```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
            Claims claims = Jwts.parser()
                .setSigningKey(jwtSecret)
                .parseClaimsJws(token)
                .getBody();
            String userId = claims.getSubject();
            SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, null));
        }
        filterChain.doFilter(request, response);
    }
}
```

### Input Validation
- Seat numbers: alphanumeric format validation
- Price: integer paise, positive
- Per-user limit: positive integer
- Idempotency key: non-empty string
- Use `@Valid` with `@Validated` annotations

### SQL Injection Prevention
- Spring Data JPA uses parameterized queries automatically
- Never use native SQL with string concatenation

## Testing Strategy

### Unit Tests (JUnit 5)
- Service layer logic
- Idempotency handling
- Per-user limit enforcement
- Seat locking logic

### Integration Tests (Spring Boot Test)
- Full API endpoints with `@WebMvcTest` or `@SpringBootTest`
- Database constraints with `@DataJpaTest`
- Transaction rollback with `@Transactional`

### Load Test (Burst Script)
- 20,000 concurrent requests
- Hot seat contention
- Idempotency key reuse
- Multi-user scenario
- Validates reconciliation invariant
- Reports distribution (confirmed/declined/5xx)

## Maven Dependencies (pom.xml)

```xml
<dependencies>
    <!-- Spring Boot Starter Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Spring Boot Starter Data JPA -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- H2 Database (in-memory) -->
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- Spring Boot Starter Actuator (health/metrics) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- Micrometer Prometheus -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>

    <!-- JWT (jjwt) -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.11.5</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.11.5</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.11.5</version>
        <scope>runtime</scope>
    </dependency>

    <!-- Logstash Logback Encoder (structured JSON logging) -->
    <dependency>
        <groupId>net.logstash.logback</groupId>
        <artifactId>logstash-logback-encoder</artifactId>
        <version>7.4</version>
    </dependency>

    <!-- Validation -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- Lombok (optional, for boilerplate reduction) -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- Testing -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## Project Structure

```
src/
├── main/
│   ├── java/
│   │   └── com/
│   │       └── seatreservation/
│   │           ├── SeatReservationApplication.java
│   │           ├── config/
│   │           │   ├── JwtConfig.java
│   │           │   ├── SecurityConfig.java
│   │           │   └── MetricsConfig.java
│   │           ├── controller/
│   │           │   ├── ShowController.java
│   │           │   ├── ReservationController.java
│   │           │   └── HealthController.java
│   │           ├── service/
│   │           │   ├── ShowService.java
│   │           │   ├── ReservationService.java
│   │           │   └── HoldExpiryScheduler.java
│   │           ├── repository/
│   │           │   ├── ShowRepository.java
│   │           │   ├── SeatRepository.java
│   │           │   └── ReservationRepository.java
│   │           ├── model/
│   │           │   ├── Show.java
│   │           │   ├── Seat.java
│   │           │   ├── Reservation.java
│   │           │   └── dto/
│   │           │       ├── CreateShowRequest.java
│   │           │       ├── ReserveSeatsRequest.java
│   │           │       └── ReservationResponse.java
│   │           ├── exception/
│   │           │   ├── SeatNotAvailableException.java
│   │           │   ├── PerUserLimitExceededException.java
│   │           │   ├── IdempotencyConflictException.java
│   │           │   └── GlobalExceptionHandler.java
│   │           └── filter/
│   │               ├── JwtAuthenticationFilter.java
│   │               ├── RequestIdFilter.java
│   │               └── LoggingFilter.java
│   └── resources/
│       ├── application.yml
│       ├── application-prod.yml
│       ├── logback-spring.xml
│       └── data.sql
└── test/
    └── java/
        └── com/
            └── seatreservation/
                ├── service/
                │   └── ReservationServiceTest.java
                └── controller/
                    └── ReservationControllerTest.java
```

## Observability

### What to Page On at 2am
1. **5xx error rate > 1%** - Indicates system failure, not normal declines
2. **DB connectivity failure** - Readiness check failing
3. **Hold expiry cleanup failures** - Could lead to seat leak
4. **Reconciliation invariant violation** - Data corruption
5. **Reservation latency p99 > 5s** - Performance degradation

### Monitoring Dashboard
- Reservation rate (confirmed/declined)
- Seat availability by show
- Per-user limit violations
- Idempotency conflicts
- DB query latency
- Error rate by status code
