package com.seatreservation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The hints are advisory: none of these may ever change what a client is told, only how fast. */
class SeatHintsTest extends AbstractApiTest {
    /** Idempotency keys are per user, so every test (a fresh instance) uses its own users and keys. */
    private final String sfx = java.util.UUID.randomUUID().toString().substring(0, 8);
    private String k(String key) { return key + sfx; }

    @Test void emptyHintsAfterARestartStillGiveTheSameAnswers() {
        String show = show(5, null);
        String a = token("a" + sfx), b = token("b" + sfx);
        assertEquals(201, reserve(show, a, k("k1"), "A1").status());
        hints.clear();
        assertFalse(hints.isTaken(show, "A1"));
        Resp lost = reserve(show, b, k("k2"), "A1");
        assertEquals(409, lost.status());
        assertEquals("seat_taken", lost.body().get("error").asText());
        assertTrue(hints.isTaken(show, "A1"), "the database decline should have been learned");
        assertDatabaseConsistent(show);
    }

    @Test void hintedRejectionHasTheSameShapeAsADatabaseRejection() {
        String show = show(5, null);
        String a = token("a" + sfx), b = token("b" + sfx);
        assertEquals(201, reserve(show, a, k("k1"), "A1").status());
        assertTrue(hints.isTaken(show, "A1"));
        Resp r = reserve(show, b, k("k2"), "A1");
        assertEquals(409, r.status());
        assertEquals("seat_taken", r.body().get("error").asText());
        assertEquals("A1", r.body().get("details").get("seat").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE user_id=?", Integer.class, "b" + sfx));
    }

    @Test void idempotentReplayStillWinsOverAHint() {
        String show = show(5, null);
        String a = token("a" + sfx);
        assertEquals(201, reserve(show, a, k("same"), "A1").status());
        assertTrue(hints.isTaken(show, "A1"));
        Resp replay = reserve(show, a, k("same"), "A1");
        assertEquals(200, replay.status(), "a retry of the winning request must replay, not 409");
    }

    @Test void cancelInvalidatesTheHintSoTheSeatIsImmediatelyBookable() {
        String show = show(5, null);
        String a = token("a" + sfx), b = token("b" + sfx);
        String id = reserve(show, a, k("k1"), "A1").body().get("reservation_id").asText();
        assertEquals(409, reserve(show, b, k("k2"), "A1").status());
        assertEquals(200, call("POST", "/reservations/" + id + "/cancel", a, null).status());
        assertFalse(hints.isTaken(show, "A1"));
        assertEquals(201, reserve(show, b, k("k3"), "A1").status());
        assertDatabaseConsistent(show);
    }

    @Test void expiredHoldInvalidatesTheHint() throws Exception {
        String show = show(5, null);
        String a = token("a" + sfx), b = token("b" + sfx);
        assertEquals(201, hold(show, a, k("k1"), "A1").status());
        assertEquals(409, reserve(show, b, k("k2"), "A1").status());
        long deadline = System.currentTimeMillis() + 10_000;
        int status = 409;
        for (int i = 0; status != 201 && System.currentTimeMillis() < deadline; i++) {
            Thread.sleep(250);
            status = reserve(show, b, k("retry" + i), "A1").status();
        }
        assertEquals(201, status, "the sweeper's release must clear the hint");
        assertDatabaseConsistent(show);
    }

    @Test void aSeatMissingFromTheHintStoreIsStillDecidedByTheDatabase() {
        String show = show(3, null);
        String a = token("a" + sfx), b = token("b" + sfx);
        reserve(show, a, k("k1"), "A1");
        hints.clear();
        assertEquals(409, reserve(show, b, k("k2"), "A1").status());
        assertEquals(201, reserve(show, b, k("k3"), "A2").status());
    }

    @Test void releaseChurnNeverLeavesAStaleHint() throws Exception {
        String show = show(3, 3);
        AtomicInteger wins = new AtomicInteger();
        parallel(40, i -> {
            String tok = token("churn" + i + sfx);
            Random rnd = new Random(i);
            for (int n = 0; n < 25; n++) {
                String seat = "A" + (1 + rnd.nextInt(3));
                Resp r = reserve(show, tok, k("c" + i + "-" + n), seat);
                if (r.status() == 201) {
                    wins.incrementAndGet();
                    assertEquals(200, call("POST", "/reservations/" + r.body().get("reservation_id").asText() + "/cancel", tok, null).status());
                } else assertEquals(409, r.status());
            }
            return null;
        });
        assertTrue(wins.get() > 0);
        assertEquals(3, showState(show).get("available").asInt(), "every win was cancelled");
        assertDatabaseConsistent(show);   // includes: no available seat carries a 'taken' hint
        for (String seat : List.of("A1", "A2", "A3")) assertFalse(hints.isTaken(show, seat));
    }

    @Test void holdAndReleaseChurnAgainstTheSweeperLeavesNoStaleHint() throws Exception {
        String show = show(4, 4);
        parallel(20, i -> {
            String tok = token("h" + i + sfx);
            for (int n = 0; n < 6; n++) {
                hold(show, tok, k("h" + i + "-" + n), "A" + (1 + (i + n) % 4));
                try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            }
            return null;
        });
        Thread.sleep(3_500);   // let every remaining 2 s hold expire
        assertEquals(4, showState(show).get("available").asInt());
        assertDatabaseConsistent(show);
    }
}
