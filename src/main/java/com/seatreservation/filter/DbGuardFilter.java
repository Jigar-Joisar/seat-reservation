package com.seatreservation.filter;

import com.seatreservation.service.DbHealthMonitor;
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

/** Fails requests that need the database fast (503 + Retry-After) while {@link DbHealthMonitor} reports it unreachable. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class DbGuardFilter extends OncePerRequestFilter {
    private final DbHealthMonitor monitor;

    public DbGuardFilter(DbHealthMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        boolean needsDb = path.startsWith("/shows") || path.startsWith("/reservations");
        if (needsDb && !monitor.isUp()) {
            res.setStatus(503);
            res.setHeader("Retry-After", "5");
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write("{\"error\":\"service_unavailable\",\"message\":\"The database is unreachable; nothing was booked. Retry with the same idempotency key.\",\"details\":{\"retryable\":true,\"circuit\":\"open\"}}");
            return;
        }
        chain.doFilter(req, res);
    }
}
