package com.seatreservation.service;

import com.seatreservation.api.ApiException;
import com.seatreservation.api.Dtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ShowService {
    public static final Pattern SEAT = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReservationMetrics metrics;
    private final int defaultLimit;
    private final int maxSeats;

    public ShowService(JdbcTemplate jdbc, TransactionTemplate tx, ReservationMetrics metrics,
                       @Value("${app.per-user-limit}") int defaultLimit, @Value("${app.max-seats-per-show}") int maxSeats) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.metrics = metrics;
        this.defaultLimit = defaultLimit;
        this.maxSeats = maxSeats;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerExistingShows() {
        jdbc.queryForList("SELECT id FROM shows", String.class).forEach(metrics::registerShow);
    }

    public ShowView create(CreateShowRequest r) {
        if (r == null || r.name() == null || r.name().isBlank() || r.name().length() > 255) throw ApiException.badRequest("name is required (max 255 chars)");
        if (r.seats() == null || r.seats().isEmpty()) throw ApiException.badRequest("seats must be a non-empty list");
        if (r.seats().size() > maxSeats) throw ApiException.badRequest("too many seats (max " + maxSeats + ")");
        if (r.pricePaise() == null || r.pricePaise() <= 0) throw ApiException.badRequest("price_paise must be a positive integer");
        int limit = r.perUserLimit() == null ? defaultLimit : r.perUserLimit();
        if (limit < 1 || limit > 1000) throw ApiException.badRequest("per_user_limit must be between 1 and 1000");
        HashSet<String> seen = new HashSet<>();
        for (String s : r.seats()) {
            if (s == null || !SEAT.matcher(s).matches()) throw ApiException.badRequest("invalid seat name: " + s);
            if (!seen.add(s)) throw ApiException.badRequest("duplicate seat: " + s);
        }
        String id = UUID.randomUUID().toString();
        tx.executeWithoutResult(st -> {
            jdbc.update("INSERT INTO shows (id, name, price_paise, per_user_limit, created_at) VALUES (?,?,?,?,?)",
                    id, r.name(), r.pricePaise(), limit, Timestamp.from(Instant.now()));
            jdbc.batchUpdate("INSERT INTO seats (show_id, seat_number, status) VALUES (?,?,'available')", r.seats(), 1000,
                    (ps, seat) -> { ps.setString(1, id); ps.setString(2, seat); });
        });
        metrics.registerShow(id);
        return get(id);
    }

    public ShowView get(String id) {
        return tx.execute(st -> {
            var row = jdbc.query("SELECT name, price_paise, per_user_limit FROM shows WHERE id = ?",
                    (rs, i) -> new Object[]{rs.getString(1), rs.getLong(2), rs.getInt(3)}, id);
            if (row.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "show_not_found", "Show not found: " + id);
            // counts are derived from the very same result set as the seat list => always a consistent snapshot
            List<SeatView> seats = new ArrayList<>();
            long[] c = new long[3];
            jdbc.query("SELECT seat_number, status FROM seats WHERE show_id = ? ORDER BY seat_number", rs -> {
                String status = rs.getString(2);
                seats.add(new SeatView(rs.getString(1), status));
                c[switch (status) { case "available" -> 0; case "held" -> 1; default -> 2; }]++;
            }, id);
            Object[] o = row.get(0);
            Counts counts = new Counts(c[0], c[1], c[2], seats.size());
            return new ShowView(id, (String) o[0], (Long) o[1], (Integer) o[2], c[0], c[1], c[2], seats.size(), counts, seats);
        });
    }
}
