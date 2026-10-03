package com.seatreservation;

import com.seatreservation.controller.HealthController;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** While the database is unreachable (as seen by the health probe) database requests fail fast with 503, and recover on their own. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DbGuardTest extends AbstractApiTest {
    @Autowired HealthController health;

    @Test void circuitOpensAfterRepeatedProbeFailuresAndRequestsFailFast() throws Exception {
        String tok = token("guard-user");
        String show = show(3, null);
        assertEquals(201, reserve(show, tok, "g1", "A1").status());

        ((HikariDataSource) ReflectionTestUtils.getField(health, "probePool")).close(); // the probe now fails; the request pool is untouched
        Resp r = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            r = reserve(show, tok, "g2", "A2");
            if (r.status() == 503) break;
            Thread.sleep(100);
        }
        assertNotNull(r);
        assertEquals(503, r.status(), "the guard should have opened: " + r.raw());
        assertEquals("service_unavailable", r.body().get("error").asText());
        assertEquals("open", r.body().get("details").get("circuit").asText());
        assertEquals("5", r.headers().firstValue("Retry-After").orElse(null));

        long t0 = System.nanoTime();
        assertEquals(503, call("GET", "/shows/" + show, null, null).status());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1000, "an open circuit must answer immediately");

        assertEquals(200, call("GET", "/health/live", null, null).status());
        assertEquals(200, call("POST", "/auth/token", null, Map.of("user_id", "x")).status());
    }
}
