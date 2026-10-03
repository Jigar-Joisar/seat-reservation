package com.seatreservation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LogsEndpointTest extends AbstractApiTest {

    @Test void requestIdFromResponseHeaderFindsTheAccessLogLine() {
        String tok = token("logger-1");
        String show = show(5, null);
        Resp r = reserve(show, tok, "k-log-1", "A1");
        assertEquals(201, r.status());
        String rid = r.headers().firstValue("X-Request-ID").orElseThrow();

        Resp logs = call("GET", "/ops/logs?request_id=" + rid, null, null);
        assertEquals(200, logs.status());
        var entries = logs.body().get("entries");
        assertTrue(entries.size() >= 2, "access line + business event expected, got " + entries);
        boolean access = false, business = false;
        for (var e : entries) {
            assertEquals(rid, e.get("request_id").asText());
            if ("access".equals(e.get("logger").asText())) { access = true; assertEquals(201, e.get("status").asInt()); assertEquals("logger-1", e.get("user_id").asText()); }
            if ("reservation confirmed".equals(e.get("message").asText())) { business = true; assertEquals(show, e.get("show_id").asText()); }
        }
        assertTrue(access && business);
    }

    @Test void neverLeaksTokensSecretsHeadersOrStackTraces() {
        String tok = token("logger-2");
        adminToken();
        String fakeJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJlMTIzNDU2";
        call("GET", "/does/not/exist/" + fakeJwt, null, null);
        call("GET", "/shows/nope", tok, null);
        callRaw("POST", "/shows/x/reserve", tok, "{not json");
        call("POST", "/auth/token", null, Map.of("user_id", "logger-2", "admin_secret", "WRONG-SECRET-VALUE"));

        Resp logs = call("GET", "/ops/logs?limit=1000", null, null);
        assertEquals(200, logs.status());
        String all = logs.raw();
        assertTrue(all.contains("[redacted]"), "the JWT in the path should have been scrubbed");
        for (String bad : new String[]{tok, admin, fakeJwt, "WRONG-SECRET-VALUE", "dev-admin-secret", "dev-only-jwt-secret", "Authorization", "Bearer", "stack_trace", "jdbc:", "password"})
            assertFalse(all.contains(bad), "log output must not contain: " + bad);
        for (var e : logs.body().get("entries"))
            e.fieldNames().forEachRemaining(f -> assertTrue(java.util.Set.of("timestamp", "level", "logger", "message", "request_id", "method", "path", "status",
                    "duration_ms", "user_id", "reservation_id", "show_id", "seats", "released").contains(f), "unexpected field " + f));
    }

    @Test void limitIsClampedAndReadingLogsDoesNotFloodThem() {
        for (int i = 0; i < 3; i++) call("GET", "/ops/logs", null, null);
        Resp one = call("GET", "/ops/logs?limit=1", null, null);
        assertEquals(1, one.body().get("count").asInt());
        assertNotEquals("/ops/logs", one.body().get("entries").get(0).path("path").asText(), "reading logs must not log itself");
        assertEquals(200, call("GET", "/ops/logs?limit=999999", null, null).status());
    }
}
