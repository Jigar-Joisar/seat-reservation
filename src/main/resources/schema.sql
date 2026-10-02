CREATE TABLE IF NOT EXISTS shows (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price_paise BIGINT NOT NULL,
    per_user_limit INT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS seats (
    show_id VARCHAR(36) NOT NULL,
    seat_number VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    user_id VARCHAR(64),
    reservation_id VARCHAR(36),
    PRIMARY KEY (show_id, seat_number)
);
CREATE INDEX IF NOT EXISTS idx_seats_reservation ON seats (reservation_id);
CREATE TABLE IF NOT EXISTS reservations (
    id VARCHAR(36) PRIMARY KEY,
    show_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    seats VARCHAR(4096) NOT NULL,
    seat_count INT NOT NULL,
    amount_paise BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_res_user_key UNIQUE (user_id, idempotency_key)
);
CREATE TABLE IF NOT EXISTS user_show_allocations (
    show_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    seats_held INT NOT NULL,
    PRIMARY KEY (show_id, user_id)
);
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;
