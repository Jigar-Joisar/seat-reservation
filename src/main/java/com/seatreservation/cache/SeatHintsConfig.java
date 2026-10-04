package com.seatreservation.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class SeatHintsConfig {
    @Bean
    SeatHints seatHints(MeterRegistry registry,
                        @Value("${app.seat-hints.mode:local}") String mode,
                        @Value("${app.seat-hints.max-entries:500000}") long maxEntries,
                        @Value("${app.seat-hints.ttl-seconds:60}") long ttlSeconds) {
        return switch (mode) {
            case "off" -> new NoopSeatHints();
            case "local" -> new LocalSeatHints(registry, maxEntries, Duration.ofSeconds(ttlSeconds));
            default -> throw new IllegalArgumentException("app.seat-hints.mode must be off or local, got: " + mode);
        };
    }
}
