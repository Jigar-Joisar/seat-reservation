package com.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ReservationApiTest extends AbstractApiTest {

    @Test void createShowShapeAndValidation() {
        String id = show(5, null);
        JsonNode s = call("GET", "/shows/" + id, null, null).body();
        assertEquals(5, s.get("total_seats").asInt());
        assertEquals(5, s.get("available").asInt());
        assertEquals("available", s.get("seats").get(0).get("status").asText());
        assertEquals(400, call("POST", "/shows", adminToken(), Map.of("name", "x", "seats", List.of("A1", "A1"), "price_paise", 1)).status());
        assertEquals(404, call("GET", "/shows/nope", null, null).status());
    }

    @Test void authRules() {
        String id = show(3, null);
        assertEquals(401, call("POST", "/shows/" + id + "/reserve", null, Map.of("seats", List.of("A1"), "idempotency_key", "k")).status());
        assertEquals(401, call("POST", "/shows/" + id + "/reserve", "garbage", Map.of("seats", List.of("A1"), "idempotency_key", "k")).status());
        assertEquals(401, call("POST", "/shows", null, Map.of("name", "x", "seats", List.of("A1"), "price_paise", 1)).status());
        assertEquals(403, call("POST", "/shows", token("bob"), Map.of("name", "x", "seats", List.of("A1"), "price_paise", 1)).status());
        assertEquals(403, call("POST", "/auth/token", null, Map.of("user_id", "x", "admin_secret", "wrong")).status());
    }

    @Test void identityComesFromTokenNotBody() {
        String id = show(3, null);
        Resp r = call("POST", "/shows/" + id + "/reserve", token("alice"),
                Map.of("seats", List.of("A1"), "idempotency_key", "spoof", "user_id", "mallory"));
        assertEquals(201, r.status());
        assertEquals("alice", r.body().get("user_id").asText());
        assertEquals("confirmed", r.body().get("status").asText());
        assertEquals(25000, r.body().get("amount_paise").asLong());
        assertEquals(403, call("POST", "/reservations/" + r.body().get("reservation_id").asText() + "/cancel", token("mallory"), null).status());
    }

    @Test void hotSeatStormExactlyOneWinner() throws Exception {
        String id = show(10, null);
        List<Resp> rs = parallel(500, i -> reserve(id, token("u" + i), "k" + i, "A1"));
        assertEquals(1, rs.stream().filter(r -> r.status() == 201).count());
        assertEquals(499, rs.stream().filter(r -> r.status() == 409).count());
        assertTrue(rs.stream().noneMatch(r -> r.status() >= 500));
        assertInvariant(id);
        assertEquals(1, call("GET", "/shows/" + id, null, null).body().get("confirmed").asInt());
    }

    @Test void crossedMultiSeatRequestsDoNotDeadlockAndAreAllOrNothing() throws Exception {
        String id = show(4, 4);
        List<Resp> rs = parallel(200, i -> i % 2 == 0 ? reserve(id, token("m" + i), "k" + i, "A1", "A2") : reserve(id, token("m" + i), "k" + i, "A2", "A1"));
        assertEquals(1, rs.stream().filter(r -> r.status() == 201).count());
        assertTrue(rs.stream().noneMatch(r -> r.status() >= 500));
        JsonNode s = call("GET", "/shows/" + id, null, null).body();
        assertEquals(2, s.get("confirmed").asInt());
        String t = token("partial");
        assertEquals(409, reserve(id, t, "p1", "A2", "A3").status());
        JsonNode s2 = call("GET", "/shows/" + id, null, null).body();
        assertEquals(2, s2.get("available").asInt());
        assertInvariant(id);
    }

    @Test void perUserLimitHoldsUnderConcurrency() throws Exception {
        String id = show(30, null);
        String t = token("greedy");
        List<Resp> rs = parallel(10, i -> reserve(id, t, "g" + i, "A" + (i + 1)));
        assertEquals(4, rs.stream().filter(r -> r.status() == 201).count());
        assertEquals(6, rs.stream().filter(r -> r.status() == 409).count());
        assertEquals(4, call("GET", "/shows/" + id, null, null).body().get("confirmed").asInt());
        assertEquals(409, reserve(id, token("big"), "b", "A20", "A21", "A22", "A23", "A24").status());
    }

    @Test void idempotencyExactlyOnce() throws Exception {
        String id = show(10, null);
        String t = token("idem");
        List<Resp> rs = parallel(50, i -> reserve(id, t, "same", "A5"));
        assertTrue(rs.stream().allMatch(r -> r.status() == 201 || r.status() == 200));
        assertEquals(1, rs.stream().filter(r -> r.status() == 201).count());
        assertEquals(1, rs.stream().map(r -> r.body().get("reservation_id").asText()).collect(Collectors.toSet()).size());
        assertEquals(1, call("GET", "/shows/" + id, null, null).body().get("confirmed").asInt());
        assertEquals(409, reserve(id, t, "same", "A6").status());
        Resp other = reserve(id, token("other"), "same", "A7");
        assertEquals(201, other.status());
        assertEquals("other", other.body().get("user_id").asText());
    }

    @Test void cancelReleasesSeatAndIsRebookable() {
        String id = show(3, 1);
        Resp r = reserve(id, token("c1"), "c", "A1");
        String rid = r.body().get("reservation_id").asText();
        assertEquals(409, reserve(id, token("c2"), "c", "A1").status());
        assertEquals(200, call("POST", "/reservations/" + rid + "/cancel", token("c1"), null).status());
        assertEquals(200, call("POST", "/reservations/" + rid + "/cancel", token("c1"), null).status());
        assertEquals(3, call("GET", "/shows/" + id, null, null).body().get("available").asInt());
        assertEquals(201, reserve(id, token("c2"), "c", "A1").status());
        assertEquals(201, reserve(id, token("c1"), "c-again", "A2").status());
        // stale cancel must not resurrect a seat now owned by c2
        call("POST", "/reservations/" + rid + "/cancel", token("c1"), null);
        assertEquals("confirmed", call("GET", "/shows/" + id, null, null).body().get("seats").get(0).get("status").asText());
        assertInvariant(id);
    }

    @Test void healthAndMetrics() {
        assertEquals(200, call("GET", "/health/live", null, null).status());
        assertEquals(200, call("GET", "/health/ready", null, null).status());
        String id = show(2, null);
        reserve(id, token("mm"), "mk", "A1");
        reserve(id, token("mm2"), "mk2", "A1");
        try {
            String body = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/actuator/prometheus")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.contains("reservations_confirmed_total"));
            assertTrue(body.contains("reservations_declined_total{reason=\"seat_taken\",}"), body.lines().filter(l -> l.startsWith("reserv")).collect(Collectors.joining("\n")));
            assertTrue(body.contains("seats_available{show_id=\"" + id + "\",} 1.0"), body.lines().filter(l -> l.startsWith("seats_")).collect(Collectors.joining("\n")));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test void holdThenConfirmAndOwnership() {
        String id = show(3, null);
        Resp h = hold(id, token("h1"), "hk", "A1");
        assertEquals(201, h.status());
        assertEquals("held", h.body().get("status").asText());
        assertNotNull(h.body().get("expires_at"));
        String rid = h.body().get("reservation_id").asText();
        assertEquals(1, call("GET", "/shows/" + id, null, null).body().get("held").asInt());
        assertEquals(409, reserve(id, token("h2"), "x", "A1").status());
        assertEquals(403, call("POST", "/reservations/" + rid + "/confirm", token("h2"), null).status());
        assertEquals("confirmed", call("POST", "/reservations/" + rid + "/confirm", token("h1"), null).body().get("status").asText());
        assertEquals(200, call("POST", "/reservations/" + rid + "/confirm", token("h1"), null).status());
        assertEquals(1, call("GET", "/shows/" + id, null, null).body().get("confirmed").asInt());
        assertInvariant(id);
    }

    @Test void holdStormExactlyOneWinner() throws Exception {
        String id = show(5, null);
        List<Resp> rs = parallel(200, i -> hold(id, token("hs" + i), "k" + i, "A1"));
        assertEquals(1, rs.stream().filter(r -> r.status() == 201).count());
        assertTrue(rs.stream().noneMatch(r -> r.status() >= 500));
        assertInvariant(id);
    }

    @Test void expiredHoldReturnsSeatAndConfirmedSeatSurvives() throws Exception {
        String id = show(3, 1);
        Resp h = hold(id, token("e1"), "e", "A1");
        String rid = h.body().get("reservation_id").asText();
        Resp keep = hold(id, token("e2"), "e", "A2");
        String keepId = keep.body().get("reservation_id").asText();
        assertEquals(200, call("POST", "/reservations/" + keepId + "/confirm", token("e2"), null).status());
        Thread.sleep(3500);
        JsonNode s = call("GET", "/shows/" + id, null, null).body();
        assertEquals(2, s.get("available").asInt());
        assertEquals(1, s.get("confirmed").asInt());
        assertEquals(0, s.get("held").asInt());
        assertEquals(409, call("POST", "/reservations/" + rid + "/confirm", token("e1"), null).status());
        assertEquals(201, reserve(id, token("e3"), "e", "A1").status());
        assertEquals(201, hold(id, token("e1"), "e-again", "A3").status());
        call("POST", "/reservations/" + rid + "/cancel", token("e1"), null);
        assertEquals("confirmed", call("GET", "/shows/" + id, null, null).body().get("seats").get(0).get("status").asText());
        assertInvariant(id);
    }
}
