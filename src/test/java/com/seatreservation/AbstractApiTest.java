package com.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Shared HTTP helpers. Every subclass talks to the real server over HTTP on a random port (in-memory H2). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class AbstractApiTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.seatreservation.cache.SeatHints hints;
    static final ObjectMapper M = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().executor(Executors.newFixedThreadPool(64)).build();
    static String admin;

    record Resp(int status, JsonNode body, HttpHeaders headers, String raw) {}

    String base() { return "http://localhost:" + port; }

    Resp call(String method, String path, String token, Object body, String... headers) {
        try {
            return callRaw(method, path, token, body == null ? null : M.writeValueAsString(body), headers);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Sends the body exactly as given (lets tests send malformed JSON). */
    Resp callRaw(String method, String path, String token, String rawBody, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json");
            if (token != null) b.header("Authorization", "Bearer " + token);
            for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
            b.method(method, rawBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(rawBody));
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode js = null;
            try { js = r.body().isBlank() ? null : M.readTree(r.body()); } catch (Exception ignored) { }
            return new Resp(r.statusCode(), js, r.headers(), r.body());
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

    Resp hold(String show, String tok, String key, String... seats) {
        return call("POST", "/shows/" + show + "/hold", tok, Map.of("seats", List.of(seats), "idempotency_key", key));
    }

    JsonNode showState(String show) { return call("GET", "/shows/" + show, null, null).body(); }

    void assertInvariant(String show) {
        JsonNode s = showState(show);
        assertEquals(s.get("total_seats").asLong(), s.get("available").asLong() + s.get("held").asLong() + s.get("confirmed").asLong());
    }

    /** Releases all tasks at the same instant to maximize contention. */
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

    /** Database-level cross-checks that must hold after ANY sequence of operations on a show. */
    void assertDatabaseConsistent(String showId) {
        // 1. a seat is available <=> it has no owner and no reservation
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE show_id=? AND status='available' AND (user_id IS NOT NULL OR reservation_id IS NOT NULL)", Long.class, showId), "available seat with owner");
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE show_id=? AND status<>'available' AND (user_id IS NULL OR reservation_id IS NULL)", Long.class, showId), "taken seat without owner");
        // 2. every taken seat belongs to a live reservation of the same user and matching state
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM seats s LEFT JOIN reservations r ON r.id=s.reservation_id WHERE s.show_id=? AND s.status<>'available' "
                + "AND (r.id IS NULL OR r.user_id<>s.user_id OR r.status<>s.status)", Long.class, showId), "seat/reservation mismatch");
        // 3. seats_held of each user == seats that user currently owns
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM user_show_allocations a WHERE a.show_id=? AND a.seats_held <> "
                + "(SELECT COUNT(*) FROM seats s WHERE s.show_id=a.show_id AND s.user_id=a.user_id AND s.status<>'available')", Long.class, showId), "allocation drift");
        // 4. no user over the limit, never negative
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM user_show_allocations a JOIN shows sh ON sh.id=a.show_id WHERE a.show_id=? AND (a.seats_held<0 OR a.seats_held>sh.per_user_limit)", Long.class, showId), "limit violated");
        // 6. a hint may only ever say "taken" for a seat that really is taken: no available seat carries one
        for (String seat : jdbc.queryForList("SELECT seat_number FROM seats WHERE show_id=? AND status='available'", String.class, showId)) {
            assertFalse(hints.isTaken(showId, seat), "stale 'taken' hint on available seat " + seat);
        }
        // 5. live reservations account for exactly the taken seats
        assertEquals(jdbc.queryForObject("SELECT COALESCE(SUM(seat_count),0) FROM reservations WHERE show_id=? AND status IN ('held','confirmed')", Long.class, showId),
                jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE show_id=? AND status<>'available'", Long.class, showId), "reservation seat_count vs taken seats");
    }
}
