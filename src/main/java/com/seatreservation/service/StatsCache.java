package com.seatreservation.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Seat counts per show, from one GROUP BY query, cached for a very short time so scrapes stay cheap. */
@Component
public class StatsCache {
    private static final long TTL_NANOS = 250_000_000L;
    private static final ReservationMetrics.Stat ZERO = new ReservationMetrics.Stat(0, 0, 0);

    private final JdbcTemplate jdbc;
    private volatile Map<String, ReservationMetrics.Stat> byShow = Map.of();
    private volatile ReservationMetrics.Stat total = ZERO;
    private volatile long loadedAt = System.nanoTime() - 2 * TTL_NANOS;

    public StatsCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void refresh() {
        if (System.nanoTime() - loadedAt < TTL_NANOS) return;
        synchronized (this) {
            if (System.nanoTime() - loadedAt < TTL_NANOS) return;
            Map<String, long[]> acc = new HashMap<>();
            try {
                jdbc.query("SELECT show_id, status, COUNT(*) FROM seats GROUP BY show_id, status", rs -> {
                    long[] a = acc.computeIfAbsent(rs.getString(1), k -> new long[3]);
                    int idx = switch (rs.getString(2)) { case "available" -> 0; case "held" -> 1; default -> 2; };
                    a[idx] += rs.getLong(3);
                });
            } catch (RuntimeException e) {
                return;
            }
            Map<String, ReservationMetrics.Stat> m = new HashMap<>();
            long a0 = 0, a1 = 0, a2 = 0;
            for (var e : acc.entrySet()) {
                long[] a = e.getValue();
                m.put(e.getKey(), new ReservationMetrics.Stat(a[0], a[1], a[2]));
                a0 += a[0]; a1 += a[1]; a2 += a[2];
            }
            byShow = m;
            total = new ReservationMetrics.Stat(a0, a1, a2);
            loadedAt = System.nanoTime();
        }
    }

    public ReservationMetrics.Stat forShow(String showId) {
        refresh();
        return byShow.getOrDefault(showId, ZERO);
    }

    public ReservationMetrics.Stat total() {
        refresh();
        return total;
    }
}
