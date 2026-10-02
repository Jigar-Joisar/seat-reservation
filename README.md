# Seat Reservation Service

A high-concurrency seat reservation service built with Java, Spring Boot, and H2 database. Designed to handle on-sale stampedes with atomic operations ensuring no double-selling, idempotency, and per-user limits.

## Features

- ✅ **Atomic seat allocation** - No double-selling, even under high concurrency
- ✅ **Idempotent reservations** - Same idempotency key reserves exactly once
- ✅ **Per-user limits** - Configurable seat limit per user per show (default: 4)
- ✅ **Deterministic locking** - Multi-seat requests lock in order to prevent deadlocks
- ✅ **Health checks** - Liveness and readiness endpoints with DB connectivity checks
- ✅ **Prometheus metrics** - Reservation counts, decline reasons, seat availability
- ✅ **Structured logging** - JSON logs with request IDs for tracing
- ✅ **JWT authentication** - Token-based user authentication
- ✅ **Containerized** - Docker support for easy deployment

## Tech Stack

- **Language:** Java 17
- **Framework:** Spring Boot 3.2.0
- **Database:** H2 (file-based with PostgreSQL compatibility mode)
- **ORM:** Spring Data JPA with Hibernate
- **Authentication:** JWT (jjwt library)
- **Metrics:** Spring Boot Actuator + Micrometer Prometheus
- **Logging:** Logback with Logstash JSON encoder
- **Build Tool:** Maven

## Quick Start

### Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- Docker (optional, for containerized deployment)

### Local Development

1. **Clone the repository**
```bash
git clone <repository-url>
cd SeatingReservation
```

2. **Build the project**
```bash
mvn clean package
```

3. **Run the application**
```bash
mvn spring-boot:run
```

The service will start on `http://localhost:8080`

### Docker Compose (Recommended)

1. **Build and run with Docker Compose**
```bash
docker-compose up --build
```

The service will start on `http://localhost:8080` with H2 database persisted in `./data`

2. **Stop the service**
```bash
docker-compose down
```

## API Documentation

### Health Endpoints

#### Liveness Check
```bash
GET /health/live
```
Returns `200 OK` if the service is running.

#### Readiness Check
```bash
GET /health/ready
```
Returns `200 OK` if the service is ready to accept requests (DB is reachable).

#### Actuator Health
```bash
GET /actuator/health
```
Detailed health information including DB connectivity.

### Create Show (Admin)
```bash
POST /shows
Content-Type: application/json

{
  "name": "friday-night",
  "seats": ["A1", "A2", "A3", "A4", "A5"],
  "price_paise": 25000
}
```

**Response (201 Created):**
```json
{
  "id": "uuid",
  "name": "friday-night",
  "price_paise": 25000,
  "per_user_limit": 4,
  "seats": [
    {"seatNumber": "A1", "status": "available"},
    {"seatNumber": "A2", "status": "available"}
  ],
  "counts": {
    "available": 5,
    "held": 0,
    "confirmed": 0,
    "total": 5
  }
}
```

### Reserve Seats (Authenticated)
```bash
POST /shows/{showId}/reserve
Content-Type: application/json
Authorization: Bearer <jwt-token>

{
  "seats": ["A1", "A2"],
  "idempotency_key": "unique-key-for-this-request"
}
```

**Response (201 Created):**
```json
{
  "reservationId": "uuid",
  "showId": "show-uuid",
  "userId": "user-id-from-token",
  "seats": ["A1", "A2"],
  "amountPaise": 50000,
  "status": "confirmed"
}
```

**Error Responses:**

- **409 Conflict** - Seat not available, per-user limit exceeded, or idempotency conflict
- **401 Unauthorized** - Missing or invalid JWT token
- **400 Bad Request** - Invalid request body

### Cancel Reservation (Authenticated)
```bash
POST /reservations/{reservationId}/cancel
Authorization: Bearer <jwt-token>
```

**Response (204 No Content)**

### Get Show State
```bash
GET /shows/{showId}
```

**Response (200 OK):**
```json
{
  "id": "show-uuid",
  "name": "friday-night",
  "price_paise": 25000,
  "per_user_limit": 4,
  "seats": [
    {"seatNumber": "A1", "status": "confirmed"},
    {"seatNumber": "A2", "status": "available"}
  ],
  "counts": {
    "available": 4,
    "held": 0,
    "confirmed": 1,
    "total": 5
  }
}
```

### Metrics
```bash
GET /actuator/metrics
GET /actuator/prometheus
```

Prometheus metrics include:
- `reservations_confirmed_total` - Total successful reservations
- `reservations_declined_total{reason="..."}` - Declined by reason
- `reservations_cancelled_total` - Total cancellations
- Various JVM and HTTP metrics

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `JWT_SECRET` | JWT signing secret | `your-secret-key-change-in-production` |
| `PER_USER_LIMIT` | Max seats per user per show | `4` |
| `HOLD_DURATION_MINUTES` | Hold expiry duration | `5` |
| `SPRING_DATASOURCE_URL` | H2 database URL | `jdbc:h2:file:./data/seats.db` |

### Application Configuration

Edit `src/main/resources/application.yml` to modify:
- Server port
- Database settings
- JWT expiration
- Logging levels

## Burst Testing

The service includes a burst testing script to simulate on-sale stampedes.

### Using Python Script (Recommended)

```bash
# Install requests library if needed
pip install requests

# Run burst test
./burst.py http://localhost:8080 --users 100 --requests 200
```

**Arguments:**
- `base_url` - Service URL (required)
- `--users` - Number of users (default: 100)
- `--requests` - Requests per user (default: 200)

**Output:**
- Reservation confirmation count
- Decline breakdown by reason
- Server error count (5xx)
- Final show state
- Reconciliation invariant validation

### Using Bash Script

```bash
./burst.sh http://localhost:8080 100 200
```

**Arguments:**
- `base_url` - Service URL (required)
- `num_users` - Number of users (default: 100)
- `requests_per_user` - Requests per user (default: 200)

## Deployment

### Render

1. **Create a new web service** on Render
2. **Connect your Git repository**
3. **Configure build settings:**
   - Build Command: `mvn clean package`
   - Start Command: `java -jar target/seat-reservation-1.0.0.jar`
4. **Add environment variables:**
   - `JWT_SECRET` - Generate a secure random string
   - `PER_USER_LIMIT` - `4`
   - `HOLD_DURATION_MINUTES` - `5`
5. **Deploy**

**Persistent Storage:**
Render's free tier includes persistent storage. The H2 database file will be stored in `/app/data`.

### Railway

1. **Create a new project** on Railway
2. **Deploy from Git repository**
3. **Add environment variables** in the dashboard
4. **Railway will automatically detect Spring Boot and configure**

### Fly.io

1. **Install Fly CLI**
```bash
curl -L https://fly.io/install.sh | sh
```

2. **Login to Fly**
```bash
fly auth login
```

3. **Launch the app**
```bash
fly launch
```

4. **Set environment variables**
```bash
fly secrets set JWT_SECRET=your-secret
fly secrets set PER_USER_LIMIT=4
fly secrets set HOLD_DURATION_MINUTES=5
```

5. **Deploy**
```bash
fly deploy
```

### Docker Deployment

```bash
# Build the image
docker build -t seat-reservation .

# Run the container
docker run -p 8080:8080 \
  -v $(pwd)/data:/app/data \
  -e JWT_SECRET=your-secret \
  -e PER_USER_LIMIT=4 \
  seat-reservation
```

## Architecture

### Atomic Operations

The service uses database-level atomic operations to ensure correctness:

1. **No Double-Sell:**
   - Unique constraint on `(show_id, seat_number)` in the seats table
   - Pessimistic locking (`SELECT FOR UPDATE`) via `@Lock(LockModeType.PESSIMISTIC_WRITE)`
   - Seats locked in deterministic order (sorted by seat_number) to prevent deadlocks

2. **Idempotency:**
   - Unique constraint on `idempotency_key` in reservations table
   - If two concurrent requests use the same key, exactly one INSERT succeeds
   - Loser retries and returns the winner's reservation

3. **Per-User Limit:**
   - Count existing confirmed reservations within the same transaction
   - Checked before seat locking to fail fast

4. **Multi-Seat Deadlock Avoidance:**
   - Seats are sorted before locking
   - Prevents circular wait condition

### Database Schema

**Shows Table:**
- `id` (UUID, primary key)
- `name` (string)
- `price_paise` (long)
- `per_user_limit` (integer)
- `created_at`, `updated_at` (timestamp)

**Seats Table:**
- `id` (UUID, primary key)
- `show_id` (foreign key)
- `seat_number` (string)
- `status` (enum: available, held, confirmed)
- `held_by_user_id`, `held_at`, `hold_expires_at`
- `confirmed_by_user_id`, `confirmed_at`, `reservation_id`
- Unique constraint on `(show_id, seat_number)`

**Reservations Table:**
- `id` (UUID, primary key)
- `show_id` (foreign key)
- `user_id` (string)
- `seats` (JSON array)
- `amount_paise` (long)
- `status` (enum: confirmed, cancelled)
- `idempotency_key` (unique)
- `created_at`, `updated_at` (timestamp)

## Testing

### Unit Tests

```bash
mvn test
```

### Integration Tests

```bash
mvn verify
```

### Burst Test

```bash
./burst.py http://localhost:8080 --users 100 --requests 200
```

## Observability

### Metrics

Access Prometheus metrics at:
```
http://localhost:8080/actuator/prometheus
```

Key metrics to monitor:
- `reservations_confirmed_total` - Reservation success rate
- `reservations_declined_total{reason="seat_taken"}` - Contention level
- `reservations_declined_total{reason="per_user_limit"}` - User limit violations
- `jvm_memory_used_bytes` - Memory usage
- `http_server_requests_seconds` - Request latency

### Logs

Logs are output in structured JSON format with:
- Timestamp
- Log level
- Request ID (for tracing)
- User ID
- Operation
- Duration
- Error details

### Health Checks

- **Liveness:** `GET /health/live` - Service is running
- **Readiness:** `GET /health/ready` - Service is ready (DB reachable)
- **Detailed:** `GET /actuator/health` - Full health information

## Correctness Guarantees

The service guarantees:

1. **No double-sell:** A seat confirmed for one user can never be confirmed for another
2. **Zero 5xx under load:** Declines are 4xx, not server errors
3. **Reconciliation invariant:** `available + held + confirmed == total_seats` always holds
4. **Idempotent retries:** Same key = one reservation; different seats on same key = 409
5. **Per-user limit:** User cannot exceed configured limit even with parallel requests
6. **Identity enforcement:** Token-derived identity only; request body spoofing has no effect

## AI Usage

This project was developed with AI assistance for:
- Architecture design and database evaluation
- Code structure and boilerplate generation
- Documentation and README creation
- Burst testing script implementation

All design decisions were reviewed and validated. The core concurrency control mechanisms (pessimistic locking, unique constraints, deterministic ordering) are standard database patterns for correctness under load.

## What's Next

Potential improvements:
1. Add real-time hold expiry (currently background task every 30s)
2. Implement actual seat release on cancellation (currently just marks reservation as cancelled)
3. Add reservation_seats junction table for better seat tracking
4. Implement proper admin authentication for show creation
5. Add rate limiting per user
6. Implement webhook notifications for reservation events
7. Add GraphQL API alternative
8. Implement caching for show state reads

## License

MIT

## Support

For issues or questions, please open an issue on the repository.
