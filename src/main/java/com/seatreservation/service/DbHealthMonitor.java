package com.seatreservation.service;

import com.seatreservation.controller.HealthController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Circuit breaker for database outages. Probes the database on the dedicated health pool (never starved by request
 * traffic) and opens after three consecutive failures; the first success closes it. While open, requests that need the
 * database are answered immediately with 503 instead of each waiting out the 60 s connection timeout.
 */
@Component
public class DbHealthMonitor {
    private static final Logger log = LoggerFactory.getLogger(DbHealthMonitor.class);
    private static final int FAILURES_TO_OPEN = 3;

    private final HealthController health;
    private volatile boolean up = true;
    private int failures;

    public DbHealthMonitor(HealthController health) {
        this.health = health;
    }

    public boolean isUp() {
        return up;
    }

    @Scheduled(fixedDelayString = "${app.db-probe-ms}")
    public void probe() {
        if (health.databaseReachable()) {
            failures = 0;
            if (!up) { up = true; log.warn("database reachable again, requests resume"); }
        } else if (++failures >= FAILURES_TO_OPEN && up) {
            up = false;
            log.warn("database unreachable for {} consecutive probes, failing database requests fast with 503", failures);
        }
    }
}
