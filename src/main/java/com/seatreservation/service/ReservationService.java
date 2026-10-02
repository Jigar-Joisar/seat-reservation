package com.seatreservation.service;

import com.seatreservation.api.ApiException;
import com.seatreservation.api.Dtos.ReservationView;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Atomic decision points (all inside one READ_COMMITTED transaction):
 *  1. per (show,user) allocation row is locked FOR UPDATE -> serializes one user's requests (limit + idempotency)
 *  2. each seat is taken with a conditional UPDATE ... WHERE status='available' -> exactly one winner per seat
 *  3. seats are always updated in sorted order -> no lock-order cycles between multi-seat requests
 */
@Service
public class ReservationService {
    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    public record Result(ReservationView view, boolean replay) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReservationMetrics metrics;

    public ReservationService(JdbcTemplate jdbc, TransactionTemplate tx, ReservationMetrics metrics) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.metrics = metrics;
    }

    public Result reserve(String showId, String userId, List<String> requested, String idemKey) {
        Timer.Sample sample = Timer.start();
        try {
            List<String> seats = validate(requested, idemKey);
            Result r = doReserve(showId, userId, seats, idemKey);
            if (r.replay()) metrics.declined("idempotent_replay");
            else metrics.confirmed();
            return r;
        } catch (ApiException e) {
            if (e.status() == HttpStatus.CONFLICT || e.error().equals("invalid_seat")) metrics.declined(e.error());
            throw e;
        } catch (org.springframework.dao.ConcurrencyFailureException | org.springframework.dao.QueryTimeoutException e) {
            metrics.declined("contention");
            throw e;
        } finally {
            sample.stop(metrics.reserveTimer());
        }
    }

    private List<String> validate(List<String> requested, String idemKey) {
        if (idemKey == null || idemKey.isBlank() || idemKey.length() > 128) throw ApiException.badRequest("idempotency_key is required (max 128 chars)");
        if (requested == null || requested.isEmpty()) throw ApiException.badRequest("seats must be a non-empty list");
        if (requested.size() > 100) throw ApiException.badRequest("too many seats in one request (max 100)");
        TreeSet<String> sorted = new TreeSet<>();
        for (String s : requested) {
            if (s == null || !ShowService.SEAT.matcher(s).matches()) throw ApiException.badRequest("invalid seat name: " + s);
            if (!sorted.add(s)) throw ApiException.badRequest("duplicate seat in request: " + s);
        }
        return new ArrayList<>(sorted);
    }

    private Result doReserve(String showId, String userId, List<String> seats, String idemKey) {
        Optional<Result> fast = replayIfExists(userId, idemKey, seats);
        if (fast.isPresent()) return fast.get();

        var show = jdbc.query("SELECT price_paise, per_user_limit FROM shows WHERE id = ?",
                (rs, i) -> new long[]{rs.getLong(1), rs.getInt(2)}, showId);
        if (show.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "show_not_found", "Show not found: " + showId);
        long price = show.get(0)[0];
        int limit = (int) show.get(0)[1];

        try {
            jdbc.update("INSERT INTO user_show_allocations (show_id, user_id, seats_held) VALUES (?,?,0)", showId, userId);
        } catch (DuplicateKeyException ignored) {
            // row already exists
        }

        try {
            return tx.execute(st -> {
                Integer held = jdbc.queryForObject(
                        "SELECT seats_held FROM user_show_allocations WHERE show_id = ? AND user_id = ? FOR UPDATE", Integer.class, showId, userId);
                Optional<Result> again = replayIfExists(userId, idemKey, seats);
                if (again.isPresent()) return again.get();
                if (held + seats.size() > limit) {
                    throw new ApiException(HttpStatus.CONFLICT, "per_user_limit", "Per-user limit exceeded",
                            Map.of("limit", limit, "currently_held", held, "requested", seats.size()));
                }
                String reservationId = UUID.randomUUID().toString();
                for (String seat : seats) {
                    int n = jdbc.update("UPDATE seats SET status = 'confirmed', user_id = ?, reservation_id = ? "
                            + "WHERE show_id = ? AND seat_number = ? AND status = 'available'", userId, reservationId, showId, seat);
                    if (n == 0) {
                        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT COUNT(*) > 0 FROM seats WHERE show_id = ? AND seat_number = ?", Boolean.class, showId, seat));
                        if (!exists) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_seat", "Unknown seat: " + seat);
                        throw new ApiException(HttpStatus.CONFLICT, "seat_taken", "Seat already taken: " + seat, Map.of("seat", seat));
                    }
                }
                long amount = Math.multiplyExact(price, (long) seats.size());
                jdbc.update("INSERT INTO reservations (id, show_id, user_id, seats, seat_count, amount_paise, status, idempotency_key, created_at) "
                                + "VALUES (?,?,?,?,?,?,'confirmed',?,?)",
                        reservationId, showId, userId, String.join(",", seats), seats.size(), amount, idemKey, Timestamp.from(Instant.now()));
                jdbc.update("UPDATE user_show_allocations SET seats_held = seats_held + ? WHERE show_id = ? AND user_id = ?", seats.size(), showId, userId);
                log.info("reservation confirmed", kv("reservation_id", reservationId), kv("show_id", showId), kv("user_id", userId), kv("seats", seats));
                return new Result(new ReservationView(reservationId, showId, userId, seats, amount, "confirmed"), false);
            });
        } catch (DuplicateKeyException e) {
            return replayIfExists(userId, idemKey, seats).orElseThrow(() -> e);
        }
    }

    private Optional<Result> replayIfExists(String userId, String idemKey, List<String> seats) {
        var rows = jdbc.query("SELECT id, show_id, seats, amount_paise, status FROM reservations WHERE user_id = ? AND idempotency_key = ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)}, userId, idemKey);
        if (rows.isEmpty()) return Optional.empty();
        Object[] r = rows.get(0);
        List<String> existing = Arrays.asList(((String) r[2]).split(","));
        if (!existing.equals(seats)) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "Idempotency key was already used with a different request body");
        }
        return Optional.of(new Result(new ReservationView((String) r[0], (String) r[1], userId, existing, (Long) r[3], (String) r[4]), true));
    }

    public ReservationView get(String reservationId, String userId) {
        var rows = jdbc.query("SELECT id, show_id, user_id, seats, amount_paise, status FROM reservations WHERE id = ?",
                (rs, i) -> new ReservationView(rs.getString(1), rs.getString(2), rs.getString(3),
                        Arrays.asList(rs.getString(4).split(",")), rs.getLong(5), rs.getString(6)), reservationId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "reservation_not_found", "Reservation not found");
        if (!rows.get(0).userId().equals(userId)) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Not your reservation");
        return rows.get(0);
    }

    /** Owner-only, idempotent. Seats are released only if they still belong to THIS reservation. */
    public ReservationView cancel(String reservationId, String userId) {
        ReservationView current = get(reservationId, userId);
        return tx.execute(st -> {
            jdbc.queryForObject("SELECT seats_held FROM user_show_allocations WHERE show_id = ? AND user_id = ? FOR UPDATE",
                    Integer.class, current.showId(), userId);
            String status = jdbc.queryForObject("SELECT status FROM reservations WHERE id = ? FOR UPDATE", String.class, reservationId);
            if ("cancelled".equals(status)) return new ReservationView(current.reservationId(), current.showId(), userId, current.seats(), current.amountPaise(), "cancelled");
            int released = jdbc.update("UPDATE seats SET status = 'available', user_id = NULL, reservation_id = NULL "
                    + "WHERE reservation_id = ? AND status = 'confirmed'", reservationId);
            jdbc.update("UPDATE reservations SET status = 'cancelled' WHERE id = ?", reservationId);
            jdbc.update("UPDATE user_show_allocations SET seats_held = seats_held - ? WHERE show_id = ? AND user_id = ?", released, current.showId(), userId);
            metrics.cancelled();
            log.info("reservation cancelled", kv("reservation_id", reservationId), kv("show_id", current.showId()), kv("user_id", userId), kv("released", released));
            return new ReservationView(current.reservationId(), current.showId(), userId, current.seats(), current.amountPaise(), "cancelled");
        });
    }
}
