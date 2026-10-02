package com.seatreservation;

import com.seatreservation.controller.HealthController;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/** Readiness must fail closed when its dependency (the database) is unreachable, while liveness stays up. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReadinessFailClosedTest extends AbstractApiTest {
    @Autowired HealthController health;

    @Test void readyReturns503WhenDatabaseIsUnreachableAndLiveStaysUp() {
        assertEquals(200, call("GET", "/health/ready", null, null).status());
        ((HikariDataSource) ReflectionTestUtils.getField(health, "probePool")).close(); // simulates losing the DB connection
        Resp ready = call("GET", "/health/ready", null, null);
        assertEquals(503, ready.status(), ready.raw());
        assertEquals("DOWN", ready.body().get("status").asText());
        assertEquals("DOWN", ready.body().get("database").asText());
        assertEquals(200, call("GET", "/health/live", null, null).status(), "liveness must not depend on the database");
    }
}
