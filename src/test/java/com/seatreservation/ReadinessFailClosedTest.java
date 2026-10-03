package com.seatreservation;

import com.seatreservation.controller.HealthController;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
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

    @Autowired DataSource mainPool;

    @Test void requestsFailClosedWith503AndRetryAfterWhenTheDatabaseIsGone() {
        String tok = token("outage-user");
        String show = show(3, null);
        assertEquals(201, reserve(show, tok, "before", "A1").status());
        ((HikariDataSource) mainPool).close(); // simulates losing the database for ordinary requests
        for (Resp r : new Resp[]{reserve(show, tok, "during", "A2"), call("GET", "/shows/" + show, null, null)}) {
            assertEquals(503, r.status(), r.raw());
            assertEquals("service_unavailable", r.body().get("error").asText());
            assertEquals("5", r.headers().firstValue("Retry-After").orElse(null));
        }
        assertEquals(200, call("GET", "/health/live", null, null).status());
        assertEquals(200, call("POST", "/auth/token", null, java.util.Map.of("user_id", "x")).status(), "token issuing needs no database");
    }
}
