package com.seatreservation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatreservation.api.ApiException;
import com.seatreservation.logging.RingBufferAppender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Read-only view of recent structured log events. Safe by construction: only an allow-list of fields is
 * copied out (never headers, bodies, tokens, stack traces or framework logs), and string values are
 * additionally scrubbed of anything that looks like a JWT, bearer credential or connection URL.
 */
@RestController
public class LogsController {
    private static final List<String> FIELDS = List.of("@timestamp", "level", "logger_name", "message", "request_id", "method", "path",
            "status", "duration_ms", "user_id", "reservation_id", "show_id", "seats", "released");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}(\\.[A-Za-z0-9_-]*){1,2}");
    private static final Pattern CRED = Pattern.compile("(?i)(bearer\\s+\\S+|(jdbc:|postgres(ql)?://)\\S+|(secret|password|passwd|token)=\\S+)");
    private static final int MAX_STRING = 300;

    private final ObjectMapper mapper;
    private final boolean enabled;

    public LogsController(ObjectMapper mapper, @Value("${app.public-logs:true}") boolean enabled) {
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @GetMapping("/ops/logs")
    public Map<String, Object> logs(@RequestParam(defaultValue = "200") int limit, @RequestParam(name = "request_id", required = false) String requestId) {
        if (!enabled) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Log endpoint is disabled");
        int max = Math.max(1, Math.min(limit, 1000));
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> lines = RingBufferAppender.snapshot();
        for (int i = lines.size() - 1; i >= 0 && out.size() < max; i--) {
            JsonNode n;
            try { n = mapper.readTree(lines.get(i)); } catch (Exception e) { continue; }
            if (requestId != null && !requestId.equals(n.path("request_id").asText())) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            for (String f : FIELDS) if (n.has(f)) e.put(f.equals("@timestamp") ? "timestamp" : f.equals("logger_name") ? "logger" : f, clean(n.get(f)));
            out.add(0, e);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("count", out.size());
        res.put("buffer_capacity", RingBufferAppender.capacityOrZero());
        res.put("entries", out);
        return res;
    }

    private static Object clean(JsonNode v) {
        if (v.isTextual()) {
            String s = CRED.matcher(JWT.matcher(v.asText()).replaceAll("[redacted]")).replaceAll("[redacted]");
            return s.length() > MAX_STRING ? s.substring(0, MAX_STRING) + "..." : s;
        }
        if (v.isArray()) { List<Object> l = new ArrayList<>(); v.forEach(x -> l.add(clean(x))); return l; }
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isNumber()) return v.asDouble();
        if (v.isBoolean()) return v.asBoolean();
        return null;
    }
}
