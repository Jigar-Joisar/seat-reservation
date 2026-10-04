package com.seatreservation;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/** The kill switch (SEAT_HINTS_MODE=off) must give identical behaviour with no hint state at all. */
@TestPropertySource(properties = "app.seat-hints.mode=off")
class SeatHintsOffTest extends AbstractApiTest {
    private final String sfx = java.util.UUID.randomUUID().toString().substring(0, 8);
    @Test void offModeStoresNothingAndStillDecidesCorrectly() throws Exception {
        assertFalse(hints.enabled());
        String show = show(5, null);
        var results = parallel(100, i -> reserve(show, token("u" + i + sfx), "k" + i + sfx, "A1").status());
        assertEquals(1, results.stream().filter(s -> s == 201).count());
        assertEquals(99, results.stream().filter(s -> s == 409).count());
        assertEquals(0, hints.size());
        assertDatabaseConsistent(show);
    }

    @Test void offModeCancelAndRebook() {
        String show = show(3, null);
        String a = token("a" + sfx), b = token("b" + sfx);
        String id = reserve(show, a, "k1" + sfx, "A1").body().get("reservation_id").asText();
        assertEquals(409, reserve(show, b, "k2" + sfx, "A1").status());
        call("POST", "/reservations/" + id + "/cancel", a, null);
        assertEquals(201, reserve(show, b, "k3" + sfx, "A1").status());
    }
}
