package com.seatreservation.controller;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.Map;

@RestController
public class HealthController {
    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private final HikariDataSource probePool;
    private final JdbcTemplate probe;

    /** Dedicated tiny pool so readiness is not starved by a saturated request pool during a burst. */
    public HealthController(DataSource main) {
        HikariDataSource m = (HikariDataSource) main;
        probePool = new HikariDataSource();
        probePool.setPoolName("health-probe");
        probePool.setJdbcUrl(m.getJdbcUrl());
        probePool.setUsername(m.getUsername());
        probePool.setPassword(m.getPassword());
        probePool.setMaximumPoolSize(2);
        probePool.setMinimumIdle(0);
        probePool.setConnectionTimeout(2000);
        probePool.setInitializationFailTimeout(-1);
        probe = new JdbcTemplate(probePool);
        probe.setQueryTimeout(2);
    }

    @GetMapping("/health/live")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    /** One probe on the dedicated pool; false if the database cannot answer within the probe timeout. */
    public boolean databaseReachable() {
        try {
            probe.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        if (databaseReachable()) return ResponseEntity.ok(Map.of("status", "UP", "database", "UP"));
        log.error("readiness check failed: database unreachable");
        return ResponseEntity.status(503).body(Map.of("status", "DOWN", "database", "DOWN"));
    }

    @PreDestroy
    void close() {
        probePool.close();
    }
}
