package com.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Input validation, error shape, authentication/authorization edge cases, request ids, response contract. */
class ValidationAndAuthTest extends AbstractApiTest {

    private String signed(String secret, String sub, long ttlMs, String role) throws Exception {
        var key = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        Date now = new Date();
        return Jwts.builder().setSubject(sub).claim("role", role).setIssuedAt(now).setExpiration(new Date(now.getTime() + ttlMs)).signWith(key, SignatureAlgorithm.HS256).compact();
    }

    private void assertError(Resp r, int status, String code) {
        assertEquals(status, r.status(), r.raw());
        assertNotNull(r.body(), "error body must be JSON: " + r.raw());
        assertEquals(code, r.body().get("error").asText(), r.raw());
        assertNotNull(r.body().get("message"));
        assertFalse(r.raw().contains("Exception"), "no stack traces / class names leaked: " + r.raw());
    }

    // ---------- create show validation ----------
    @Test void createShowRejectsBadInput() {
        String a = adminToken();
        List<Map<String, Object>> bad = List.of(
                Map.of("seats", List.of("A1"), "price_paise", 100),                                          // no name
                Map.of("name", " ", "seats", List.of("A1"), "price_paise", 100),                             // blank name
                Map.of("name", "x", "price_paise", 100),                                                      // no seats
                Map.of("name", "x", "seats", List.of(), "price_paise", 100),                                 // empty seats
                Map.of("name", "x", "seats", List.of("A1")),                                                  // no price
                Map.of("name", "x", "seats", List.of("A1"), "price_paise", 0),                               // zero price
                Map.of("name", "x", "seats", List.of("A1"), "price_paise", -5),                              // negative price
                Map.of("name", "x", "seats", List.of("A1"), "price_paise", Long.MAX_VALUE),                  // overflow bait
                Map.of("name", "x", "seats", List.of("A1", "A1"), "price_paise", 100),                       // duplicate seat
                Map.of("name", "x", "seats", List.of("A 1"), "price_paise", 100),                            // space in seat
                Map.of("name", "x", "seats", List.of("../etc"), "price_paise", 100),                         // path chars
                Map.of("name", "x", "seats", List.of("A".repeat(33)), "price_paise", 100),                   // too long
                Map.of("name", "x", "seats", List.of("A1"), "price_paise", 100, "per_user_limit", 0),        // limit 0
                Map.of("name", "x".repeat(256), "seats", List.of("A1"), "price_paise", 100));                // name too long
        for (Map<String, Object> b : bad) assertError(call("POST", "/shows", a, b), 400, "invalid_request");
    }

    @Test void moneyIsIntegerPaiseNeverFloat() {
        String a = adminToken();
        Resp r = callRaw("POST", "/shows", a, "{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":250.75}");
        assertEquals(400, r.status(), "fractional price must be rejected, not truncated: " + r.raw());
        Resp ok = call("POST", "/shows", a, Map.of("name", "x", "seats", List.of("A1", "A2", "A3"), "price_paise", 25000));
        assertTrue(ok.body().get("price_paise").isIntegralNumber());
    }

    @Test void malformedJsonAndWrongTypesAreCleanFourHundreds() {
        String a = adminToken(), u = token("v1");
        String id = show(2, null);
        for (String body : new String[]{"{", "not json", "[]", "{\"name\":123}", "{\"seats\":\"A1\"}", ""}) {
            Resp r = callRaw("POST", "/shows", a, body);
            assertTrue(r.status() == 400, "POST /shows body=" + body + " -> " + r.status() + " " + r.raw());
        }
        for (String body : new String[]{"{", "{\"seats\":\"A1\",\"idempotency_key\":\"k\"}", "{\"seats\":[1,2],\"idempotency_key\":\"k\"}", "{\"seats\":[null],\"idempotency_key\":\"k\"}"}) {
            Resp r = callRaw("POST", "/shows/" + id + "/reserve", u, body);
            assertEquals(400, r.status(), "reserve body=" + body + " -> " + r.raw());
        }
        assertEquals(400, callRaw("POST", "/auth/token", null, "{").status());
    }

    // ---------- reserve validation ----------
    @Test void reserveRejectsBadRequestsWithoutSideEffects() {
        String id = show(5, null);
        String u = token("v2");
        assertError(call("POST", "/shows/" + id + "/reserve", u, Map.of("seats", List.of("A1"))), 400, "invalid_request");                       // no key
        assertError(call("POST", "/shows/" + id + "/reserve", u, Map.of("seats", List.of("A1"), "idempotency_key", " ")), 400, "invalid_request");
        assertError(call("POST", "/shows/" + id + "/reserve", u, Map.of("seats", List.of("A1"), "idempotency_key", "k".repeat(129))), 400, "invalid_request");
        assertError(call("POST", "/shows/" + id + "/reserve", u, Map.of("seats", List.of(), "idempotency_key", "k")), 400, "invalid_request");
        assertError(call("POST", "/shows/" + id + "/reserve", u, Map.of("idempotency_key", "k")), 400, "invalid_request");
        assertError(call("POST", "/shows/" + id + "/reserve", u, Map.of("seats", List.of("A1", "A1"), "idempotency_key", "k")), 400, "invalid_request");
        assertError(call("POST", "/shows/" + id + "/reserve", u, Map.of("seats", List.of("A 1"), "idempotency_key", "k")), 400, "invalid_request");
        assertError(call("POST", "/shows/" + id + "/reserve", u, Map.of("seats", List.of("Z99"), "idempotency_key", "k")), 400, "invalid_seat");
        assertError(call("POST", "/shows/does-not-exist/reserve", u, Map.of("seats", List.of("A1"), "idempotency_key", "k")), 404, "show_not_found");
        assertEquals(5, showState(id).get("available").asInt(), "bad requests must not take seats");
        assertError(call("POST", "/shows/" + id + "/reserve", u, Map.of("seats", List.of("A1", "Z99"), "idempotency_key", "mix")), 400, "invalid_seat");
        assertEquals(5, showState(id).get("available").asInt(), "valid+invalid seat mix must be all-or-nothing");
        assertDatabaseConsistent(id);
    }

    @Test void reserveResponseContractAndAmounts() {
        String id = show(5, null);
        Resp r = reserve(id, token("contract"), "c", "A3", "A1", "A2");
        assertEquals(201, r.status());
        assertEquals(new TreeSet<>(List.of("reservation_id", "show_id", "user_id", "seats", "amount_paise", "status")), keys(r.body()));
        assertEquals(75000, r.body().get("amount_paise").asLong());
        assertEquals(List.of("A1", "A2", "A3"), seatsOf(r.body()), "seats are returned sorted");
        assertEquals(id, r.body().get("show_id").asText());
        assertEquals("confirmed", r.body().get("status").asText());
        assertEquals("contract", r.body().get("user_id").asText());
    }

    private static Set<String> keys(JsonNode n) { Set<String> s = new TreeSet<>(); n.fieldNames().forEachRemaining(s::add); return s; }
    private static List<String> seatsOf(JsonNode n) { List<String> l = new ArrayList<>(); n.get("seats").forEach(x -> l.add(x.asText())); return l; }

    // ---------- idempotency key transport ----------
    @Test void idempotencyKeyWorksViaHeaderAndMismatchIsRejected() {
        String id = show(5, null);
        String u = token("hdr");
        Resp a = call("POST", "/shows/" + id + "/reserve", u, Map.of("seats", List.of("A1")), "Idempotency-Key", "hk-1");
        assertEquals(201, a.status(), a.raw());
        Resp b = call("POST", "/shows/" + id + "/reserve", u, Map.of("seats", List.of("A1")), "Idempotency-Key", "hk-1");
        assertEquals(200, b.status());
        assertEquals("true", b.headers().firstValue("Idempotent-Replay").orElse(""));
        assertEquals(a.body().get("reservation_id"), b.body().get("reservation_id"));
        Resp c = call("POST", "/shows/" + id + "/reserve", u, Map.of("seats", List.of("A1"), "idempotency_key", "body-key"), "Idempotency-Key", "other-key");
        assertError(c, 400, "invalid_request");
        Resp d = call("POST", "/shows/" + id + "/reserve", u, Map.of("seats", List.of("A1"), "idempotency_key", "hk-1"));
        assertEquals(200, d.status(), "header key and body key are the same namespace");
        assertEquals(1, showState(id).get("confirmed").asInt());
    }

    @Test void idempotentReplayIsOrderInsensitiveButBodySensitive() {
        String id = show(6, null);
        String u = token("order");
        assertEquals(201, reserve(id, u, "k", "A2", "A1").status());
        assertEquals(200, reserve(id, u, "k", "A1", "A2").status(), "same seat set in a different order is the same request");
        assertError(reserve(id, u, "k", "A1"), 409, "idempotency_conflict");
        assertError(reserve(id, u, "k", "A1", "A2", "A3"), 409, "idempotency_conflict");
        assertEquals(2, showState(id).get("confirmed").asInt());
    }

    @Test void declinedRequestsDoNotBurnTheIdempotencyKey() {
        String id = show(3, 4);
        assertEquals(201, reserve(id, token("owner"), "o", "A1").status());
        String u = token("retrier");
        assertError(reserve(id, u, "rk", "A1"), 409, "seat_taken");
        Resp cancel = call("POST", "/reservations/" + reserve(id, token("owner"), "o", "A1").body().get("reservation_id").asText() + "/cancel", token("owner"), null);
        assertEquals(200, cancel.status());
        assertEquals(201, reserve(id, u, "rk", "A1").status(), "a retry after a decline can succeed once the seat is free");
    }

    // ---------- authentication ----------
    @Test void tokenEndpointValidation() {
        assertEquals(400, call("POST", "/auth/token", null, Map.of("user_id", "bad user!")).status());
        assertEquals(400, call("POST", "/auth/token", null, Map.of("user_id", "a".repeat(65))).status());
        assertEquals(400, call("POST", "/auth/token", null, Map.of()).status());
        assertEquals(403, call("POST", "/auth/token", null, Map.of("user_id", "x", "admin_secret", "")).status());
        Resp u = call("POST", "/auth/token", null, Map.of("user_id", "admin"));
        assertEquals("user", u.body().get("role").asText(), "being named admin does not make you admin");
        assertEquals(403, call("POST", "/shows", u.body().get("token").asText(), Map.of("name", "x", "seats", List.of("A1"), "price_paise", 1)).status());
    }

    @Test void badTokensAreRejected() throws Exception {
        String id = show(2, null);
        String path = "/shows/" + id + "/reserve";
        Map<String, Object> body = Map.of("seats", List.of("A1"), "idempotency_key", "t");
        String good = token("tok");
        String[] bad = {
                signed("some-other-secret", "tok", 60_000, "user"),                       // wrong signing key
                signed("dev-only-jwt-secret-change-me", "tok", -60_000, "user"),           // expired
                good.substring(0, good.length() - 3) + (good.endsWith("AAA") ? "BBB" : "AAA"), // tampered signature
                good.substring(0, good.lastIndexOf('.')),                                  // signature stripped
                "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ0b2sifQ.",                                   // alg=none
                "garbage", ""};
        for (String t : bad) assertError(call("POST", path, t.isEmpty() ? null : t, body), 401, "unauthorized");
        // wrong scheme / missing header
        assertEquals(401, call("POST", path, null, body, "Authorization", "Basic dXNlcjpwYXNz").status());
        assertEquals(401, call("POST", path, null, body, "Authorization", "Bearer").status());
        assertEquals(401, call("POST", path, null, body).status());
        assertEquals(2, showState(id).get("available").asInt(), "nothing was reserved by unauthenticated calls");
    }

    @Test void adminTokenCreatesShowsAndUserTokenCannot() throws Exception {
        Map<String, Object> req = Map.of("name", "x", "seats", List.of("A1"), "price_paise", 1);
        assertError(call("POST", "/shows", null, req), 401, "unauthorized");
        assertError(call("POST", "/shows", token("plain"), req), 403, "forbidden");
        assertEquals(201, call("POST", "/shows", adminToken(), req).status());
        // an admin-role token signed with a wrong key is not trusted
        assertError(call("POST", "/shows", signed("nope", "evil", 60_000, "admin"), req), 401, "unauthorized");
    }

    @Test void publicEndpointsNeedNoToken() {
        String id = show(1, null);
        assertEquals(200, call("GET", "/shows/" + id, null, null).status());
        assertEquals(200, call("GET", "/health/live", null, null).status());
        assertEquals(200, call("GET", "/health/ready", null, null).status());
        assertEquals(200, call("GET", "/actuator/prometheus", null, null).status());
        assertEquals(200, call("GET", "/openapi.yaml", null, null).status());
        assertEquals(200, call("GET", "/", null, null).status());
    }

    // ---------- reservations: ownership ----------
    @Test void reservationReadAndCancelAreOwnerOnly() {
        String id = show(3, null);
        Resp r = reserve(id, token("own1"), "own", "A1");
        String rid = r.body().get("reservation_id").asText();
        assertEquals(200, call("GET", "/reservations/" + rid, token("own1"), null).status());
        assertError(call("GET", "/reservations/" + rid, token("own2"), null), 403, "forbidden");
        assertError(call("GET", "/reservations/" + rid, null, null), 401, "unauthorized");
        assertError(call("GET", "/reservations/nope", token("own1"), null), 404, "reservation_not_found");
        assertError(call("POST", "/reservations/nope/cancel", token("own1"), null), 404, "reservation_not_found");
        assertError(call("POST", "/reservations/nope/confirm", token("own1"), null), 404, "reservation_not_found");
        assertError(call("POST", "/reservations/" + rid + "/cancel", token("own2"), null), 403, "forbidden");
        assertError(call("POST", "/reservations/" + rid + "/confirm", token("own2"), null), 403, "forbidden");
        assertEquals(1, showState(id).get("confirmed").asInt(), "failed ownership checks must not change state");
    }

    @Test void cancelFreesPerUserCapacityAndCountsCorrectly() {
        String id = show(10, 2);
        String u = token("cap");
        Resp r1 = reserve(id, u, "1", "A1", "A2");
        assertError(reserve(id, u, "2", "A3"), 409, "per_user_limit");
        assertEquals(200, call("POST", "/reservations/" + r1.body().get("reservation_id").asText() + "/cancel", u, null).status());
        assertEquals(201, reserve(id, u, "3", "A3", "A4").status());
        assertError(reserve(id, u, "4", "A5"), 409, "per_user_limit");
        assertDatabaseConsistent(id);
    }

    @Test void perUserLimitIsPerShowNotGlobal() {
        String s1 = show(5, 1), s2 = show(5, 1);
        String u = token("two-shows");
        assertEquals(201, reserve(s1, u, "a", "A1").status());
        assertEquals(201, reserve(s2, u, "b", "A1").status());
        assertError(reserve(s1, u, "c", "A2"), 409, "per_user_limit");
    }

    @Test void limitOnShowIsRespectedAndDefaultsToFour() {
        String id = show(10, null);
        assertEquals(4, showState(id).get("per_user_limit").asInt());
        assertEquals(1, showState(show(3, 1)).get("per_user_limit").asInt());
        assertError(reserve(id, token("five"), "k", "A1", "A2", "A3", "A4", "A5"), 409, "per_user_limit");
        assertEquals(10, showState(id).get("available").asInt());
    }

    // ---------- request id / generic http behaviour ----------
    @Test void requestIdIsGeneratedEchoedAndSanitized() {
        Resp gen = call("GET", "/health/live", null, null);
        String generated = gen.headers().firstValue("X-Request-ID").orElseThrow();
        assertTrue(generated.length() >= 16);
        Resp echo = call("GET", "/health/live", null, null, "X-Request-ID", "trace-abc.123");
        assertEquals("trace-abc.123", echo.headers().firstValue("X-Request-ID").orElseThrow());
        Resp junk = call("GET", "/health/live", null, null, "X-Request-ID", "has spaces & <script>");
        assertNotEquals("has spaces & <script>", junk.headers().firstValue("X-Request-ID").orElseThrow());
        Resp err = call("GET", "/shows/missing", null, null, "X-Request-ID", "err-1");
        assertEquals("err-1", err.headers().firstValue("X-Request-ID").orElseThrow(), "error responses carry the id too");
    }

    @Test void unknownRoutesAndMethodsAreCleanErrors() {
        assertEquals(404, call("GET", "/nope", null, null).status());
        assertEquals(405, call("PUT", "/shows/x", null, Map.of()).status());
        assertEquals(405, call("GET", "/auth/token", null, null).status());
        Resp r = call("GET", "/shows/does-not-exist", null, null);
        assertError(r, 404, "show_not_found");
    }

    @Test void showStateIsSortedAndCountsAddUp() {
        String id = show(12, null);
        reserve(id, token("sorted"), "s", "A7");
        JsonNode s = showState(id);
        List<String> names = new ArrayList<>();
        s.get("seats").forEach(x -> names.add(x.get("seat").asText()));
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        assertEquals(sorted, names);
        assertEquals(12, s.get("total_seats").asInt());
        assertEquals(12, s.get("counts").get("total_seats").asInt());
        assertEquals(s.get("available").asInt(), s.get("counts").get("available").asInt());
        long taken = names.stream().filter(n -> n.equals("A7")).count();
        assertEquals(1, taken);
        assertEquals("confirmed", s.get("seats").get(names.indexOf("A7")).get("status").asText());
    }
}
