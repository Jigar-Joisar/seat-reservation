package com.seatreservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Keeps static/openapi.yaml honest: it must describe exactly the endpoints the controllers expose. */
class OpenApiContractTest extends AbstractApiTest {
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;

    @SuppressWarnings("unchecked")
    private Map<String, Object> spec() throws Exception {
        try (var in = new ClassPathResource("static/openapi.yaml").getInputStream()) { return new Yaml().load(in); }
    }

    private static String norm(String p) { return p.replaceAll("\\{[^}]+}", "{}"); }

    @SuppressWarnings("unchecked")
    private Set<String> specOperations(Map<String, Object> spec) {
        Set<String> ops = new TreeSet<>();
        ((Map<String, Map<String, Object>>) spec.get("paths")).forEach((path, item) ->
                item.keySet().stream().filter(k -> List.of("get", "post", "put", "delete", "patch").contains(k))
                        .forEach(m -> ops.add(m.toUpperCase() + " " + norm(path))));
        return ops;
    }

    private Set<String> controllerOperations() {
        Set<String> ops = new TreeSet<>();
        for (var e : mapping.getHandlerMethods().entrySet()) {
            if (!e.getValue().getBeanType().getPackageName().equals("com.seatreservation.controller")) continue;
            RequestMappingInfo info = e.getKey();
            for (String p : info.getPathPatternsCondition().getPatternValues())
                for (RequestMethod m : info.getMethodsCondition().getMethods()) ops.add(m.name() + " " + norm(p));
        }
        return ops;
    }

    @Test void specDocumentsEveryControllerEndpointAndNothingElse() throws Exception {
        Set<String> inSpec = specOperations(spec());
        Set<String> inCode = controllerOperations();
        inCode.add("GET /actuator/prometheus"); // provided by actuator, documented on purpose
        Set<String> undocumented = new TreeSet<>(inCode); undocumented.removeAll(inSpec);
        Set<String> phantom = new TreeSet<>(inSpec); phantom.removeAll(inCode);
        assertTrue(undocumented.isEmpty(), "endpoints missing from openapi.yaml: " + undocumented);
        assertTrue(phantom.isEmpty(), "openapi.yaml documents endpoints that do not exist: " + phantom);
    }

    @Test @SuppressWarnings("unchecked") void everyOperationIsWellFormed() throws Exception {
        Map<String, Object> spec = spec();
        assertTrue(String.valueOf(spec.get("openapi")).startsWith("3."));
        Set<String> ids = new HashSet<>();
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
        for (var pe : paths.entrySet()) for (var oe : pe.getValue().entrySet()) {
            if (!(oe.getValue() instanceof Map)) continue;
            Map<String, Object> op = (Map<String, Object>) oe.getValue();
            String where = oe.getKey().toUpperCase() + " " + pe.getKey();
            assertNotNull(op.get("operationId"), where + " needs an operationId");
            assertTrue(ids.add((String) op.get("operationId")), "duplicate operationId " + op.get("operationId"));
            assertNotNull(op.get("summary"), where + " needs a summary");
            assertNotNull(op.get("tags"), where + " needs tags");
            Map<String, Object> responses = (Map<String, Object>) op.get("responses");
            assertTrue(responses != null && !responses.isEmpty(), where + " needs responses");
            boolean secured = op.get("security") instanceof java.util.Collection<?> c && !c.isEmpty();
            assertNotNull(op.get("security"), where + " must declare security explicitly (use [] for public)");
            boolean mustBeSecured = pe.getKey().equals("/shows") && oe.getKey().equals("post") || pe.getKey().startsWith("/reservations")
                    || pe.getKey().equals("/shows/{id}/reserve") || pe.getKey().equals("/shows/{id}/hold");
            assertEquals(mustBeSecured, secured, where + " security declaration must match AuthFilter");
            if (secured) assertTrue(responses.containsKey("401"), where + " must document 401");
            if (pe.getKey().equals("/shows") && oe.getKey().equals("post")) assertTrue(responses.containsKey("403"), "admin endpoint must document 403");
        }
    }

    @Test @SuppressWarnings("unchecked") void referencedComponentsExist() throws Exception {
        String yaml = new String(new ClassPathResource("static/openapi.yaml").getInputStream().readAllBytes());
        Map<String, Object> comps = (Map<String, Object>) spec().get("components");
        var m = java.util.regex.Pattern.compile("#/components/(\\w+)/(\\w+)").matcher(yaml);
        while (m.find()) {
            Map<String, Object> section = (Map<String, Object>) comps.get(m.group(1));
            assertTrue(section != null && section.containsKey(m.group(2)), "dangling $ref " + m.group(0));
        }
    }

    /** The documented error codes must be the ones the service really emits. */
    @Test void documentedErrorCodesAreReal() throws Exception {
        String yaml = new String(new ClassPathResource("static/openapi.yaml").getInputStream().readAllBytes());
        for (String code : List.of("seat_taken", "per_user_limit", "idempotency_conflict", "contention", "hold_expired", "invalid_request", "invalid_seat",
                "unauthorized", "forbidden", "show_not_found", "reservation_not_found"))
            assertTrue(yaml.contains(code), "openapi.yaml should mention error code " + code);
        // and the real service emits them
        String id = show(3, 1);
        assertEquals(201, reserve(id, token("oa"), "k1", "A1").status());
        assertEquals("per_user_limit", reserve(id, token("oa"), "k2", "A2").body().get("error").asText());
        assertEquals("seat_taken", reserve(id, token("ob"), "k3", "A1").body().get("error").asText());
        assertEquals("show_not_found", call("GET", "/shows/zzz", null, null).body().get("error").asText());
    }
}
