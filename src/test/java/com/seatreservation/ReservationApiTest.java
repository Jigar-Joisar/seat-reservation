package com.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReservationApiTest {
    @LocalServerPort int port;
    static final ObjectMapper M = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().executor(Executors.newFixedThreadPool(64)).build();
    static String admin;

    record Resp(int status, JsonNode body) {}

    String base() { return "http://localhost:" + port; }

    Resp call(String method, String path, String token, Object body, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json");
            if (token != null) b.header("Authorization", "Bearer " + token);
            for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)));
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body().isBlank() ? null : M.readTree(r.body()));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    String token(String user) { return call("POST", "/auth/token", null, Map.of("user_id", user)).body().get("token").asText(); }

    String adminToken() {
        if (admin == null) admin = call("POST", "/auth/token", null, Map.of("user_id", "admin", "admin_secret", "dev-admin-secret")).body().get("token").asText();
        return admin;
    }

    String show(int seats, Integer limit) {
        Map<String, Object> req = new HashMap<>(Map.of("name", "t", "seats", IntStream.rangeClosed(1, seats).mapToObj(i -> "A" + i).toList(), "price_paise", 25000));
        if (limit != null) req.put("per_user_limit", limit);
        Resp r = call("POST", "/shows", adminToken(), req);
        assertEquals(201, r.status(), String.valueOf(r.body()));
        return r.body().get("id").asText();
    }

    Resp reserve(String show, String tok, String key, String... seats) {
        return call("POST", "/shows/" + show + "/reserve", tok, Map.of("seats", List.of(seats), "idempotency_key", key));
    }

    void assertInvariant(String show) {
        JsonNode s = call("GET", "/shows/" + show, null, null).body();
        assertEquals(s.get("total_seats").asLong(), s.get("available").asLong() + s.get("held").asLong() + s.get("confirmed").asLong());
    }

    <T> List<T> parallel(int n, java.util.function.IntFunction<T> f) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(n, 128));
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) { int k = i; fs.add(ex.submit(() -> { go.await(); return f.apply(k); })); }
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f1 : fs) out.add(f1.get());
        ex.shutdown();
        return out;
    }

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
}
