package com.seatreservation.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

@Component
public class ReservationMetrics {
    public static final List<String> REASONS = List.of("seat_taken", "per_user_limit", "idempotent_replay",
            "idempotency_conflict", "contention", "invalid_seat");

    public record Stat(long available, long held, long confirmed) {}

    private final MeterRegistry registry;
    private final Counter confirmed;
    private final Counter cancelled;
    private final Map<String, Counter> declined = new ConcurrentHashMap<>();
    private final Set<String> registeredShows = ConcurrentHashMap.newKeySet();
    private final Timer reserveTimer;
    private final StatsCache stats;

    public ReservationMetrics(MeterRegistry registry, StatsCache stats) {
        this.registry = registry;
        this.stats = stats;
        this.confirmed = Counter.builder("reservations.confirmed").description("Reservations confirmed").register(registry);
        this.cancelled = Counter.builder("reservations.cancelled").description("Reservations cancelled").register(registry);
        for (String r : REASONS) declined.put(r, Counter.builder("reservations.declined").tag("reason", r).register(registry));
        this.reserveTimer = Timer.builder("reservation.duration").publishPercentileHistogram().register(registry);
        Gauge.builder("seats.available.total", stats, s -> s.total().available()).register(registry);
        Gauge.builder("seats.held.total", stats, s -> s.total().held()).register(registry);
        Gauge.builder("seats.confirmed.total", stats, s -> s.total().confirmed()).register(registry);
    }

    public void confirmed() { confirmed.increment(); }
    public void cancelled() { cancelled.increment(); }
    public void declined(String reason) { declined.get(reason).increment(); }
    public Timer reserveTimer() { return reserveTimer; }

    public void registerShow(String showId) {
        if (!registeredShows.add(showId)) return;
        gauge("seats.available", showId, Stat::available);
        gauge("seats.held", showId, Stat::held);
        gauge("seats.confirmed", showId, Stat::confirmed);
    }

    private void gauge(String name, String showId, ToDoubleFunction<Stat> f) {
        Gauge.builder(name, () -> f.applyAsDouble(stats.forShow(showId))).tag("show_id", showId).register(registry);
    }
}
