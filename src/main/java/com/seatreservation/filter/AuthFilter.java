package com.seatreservation.filter;

import com.seatreservation.config.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthFilter extends OncePerRequestFilter {
    public static final String USER_ATTR = "auth.user";
    private static final Pattern RESERVE = Pattern.compile("^/shows/[^/]+/reserve/?$");

    private final JwtService jwt;

    public AuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        boolean adminOnly = "POST".equals(req.getMethod()) && (path.equals("/shows") || path.equals("/shows/"));
        boolean userOnly = RESERVE.matcher(path).matches() || path.startsWith("/reservations");
        if (!adminOnly && !userOnly) {
            chain.doFilter(req, res);
            return;
        }
        String header = req.getHeader("Authorization");
        Optional<JwtService.Principal> p = header != null && header.startsWith("Bearer ")
                ? jwt.parse(header.substring(7).trim()) : Optional.empty();
        if (p.isEmpty()) {
            write(res, 401, "unauthorized", "Missing or invalid bearer token");
            return;
        }
        if (adminOnly && !p.get().admin()) {
            write(res, 403, "forbidden", "Admin role required");
            return;
        }
        req.setAttribute(USER_ATTR, p.get().userId());
        chain.doFilter(req, res);
    }

    private static void write(HttpServletResponse res, int status, String error, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}
