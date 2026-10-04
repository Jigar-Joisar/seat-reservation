package com.seatreservation;

import com.seatreservation.cache.LocalSeatHints;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalSeatHintsTest {
    private static LocalSeatHints store(long max, Duration ttl) { return new LocalSeatHints(new SimpleMeterRegistry(), max, ttl); }

    @Test void learnThenReleaseRemoves() {
        var h = store(100, Duration.ofMinutes(1));
        h.learnTaken("s", List.of("A1", "A2"), h.epoch());
        assertTrue(h.isTaken("s", "A1"));
        h.released("s", List.of("A1"));
        assertFalse(h.isTaken("s", "A1"));
        assertTrue(h.isTaken("s", "A2"));
    }

    @Test void aHintLearnedFromAReadThatPredatesARelease_isDropped() {
        var h = store(100, Duration.ofMinutes(1));
        long observed = h.epoch();            // the reader looked at the database here
        h.released("s", List.of("A1"));       // a release committed meanwhile
        h.learnTaken("s", List.of("A1"), observed);
        assertFalse(h.isTaken("s", "A1"), "otherwise the hint would outlive the release");
    }

    @Test void evictionOnlyLosesHints() {
        var h = store(2, Duration.ofMinutes(1));
        for (int i = 0; i < 200; i++) h.learnTaken("s", List.of("A" + i), h.epoch());
        assertTrue(h.size() <= 4, "size bound must hold, was " + h.size());
    }

    @Test void entriesExpireByTtl() throws Exception {
        var h = store(100, Duration.ofMillis(150));
        h.learnTaken("s", List.of("A1"), h.epoch());
        assertTrue(h.isTaken("s", "A1"));
        Thread.sleep(400);
        assertFalse(h.isTaken("s", "A1"));
    }

    @Test void clearBehavesLikeARestart() {
        var h = store(100, Duration.ofMinutes(1));
        h.learnTaken("s", List.of("A1"), h.epoch());
        h.clear();
        assertFalse(h.isTaken("s", "A1"));
        assertEquals(0, h.size());
    }
}
