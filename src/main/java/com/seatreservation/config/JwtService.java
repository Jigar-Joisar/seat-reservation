package com.seatreservation.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Optional;

@Component
public class JwtService {
    public record Principal(String userId, boolean admin) {}

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${app.jwt.secret}") String secret, @Value("${app.jwt.ttl-seconds}") long ttlSeconds) throws NoSuchAlgorithmException {
        this.key = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(String userId, boolean admin) {
        Date now = new Date();
        return Jwts.builder().setSubject(userId).claim("role", admin ? "admin" : "user")
                .setIssuedAt(now).setExpiration(new Date(now.getTime() + ttlSeconds * 1000))
                .signWith(key).compact();
    }

    public Optional<Principal> parse(String token) {
        try {
            Claims c = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
            if (c.getSubject() == null || c.getSubject().isBlank()) return Optional.empty();
            return Optional.of(new Principal(c.getSubject(), "admin".equals(c.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
