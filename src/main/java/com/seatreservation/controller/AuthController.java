package com.seatreservation.controller;

import com.seatreservation.api.ApiException;
import com.seatreservation.api.Dtos.TokenRequest;
import com.seatreservation.config.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Pattern;

/** Demo token issuer: the challenge ships no identity provider, so this mints user JWTs (admin needs ADMIN_SECRET). */
@RestController
public class AuthController {
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9_.@-]{1,64}");
    private final JwtService jwt;
    private final String adminSecret;

    public AuthController(JwtService jwt, @Value("${app.admin-secret}") String adminSecret) {
        this.jwt = jwt;
        this.adminSecret = adminSecret;
    }

    @PostMapping("/auth/token")
    public Map<String, Object> token(@RequestBody TokenRequest req) {
        if (req.userId() == null || !USER.matcher(req.userId()).matches()) throw ApiException.badRequest("user_id must match [A-Za-z0-9_.@-]{1,64}");
        boolean admin = false;
        if (req.adminSecret() != null) {
            admin = MessageDigest.isEqual(req.adminSecret().getBytes(StandardCharsets.UTF_8), adminSecret.getBytes(StandardCharsets.UTF_8));
            if (!admin) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Invalid admin secret");
        }
        return Map.of("token", jwt.issue(req.userId(), admin), "user_id", req.userId(), "role", admin ? "admin" : "user");
    }
}
