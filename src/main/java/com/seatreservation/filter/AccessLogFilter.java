package com.seatreservation.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AccessLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            String path = req.getRequestURI();
            boolean noisy = path.startsWith("/actuator") || path.startsWith("/health") || path.startsWith("/ops/logs");
            if (!noisy || res.getStatus() >= 500) {
                log.info("request", kv("method", req.getMethod()), kv("path", path), kv("status", res.getStatus()),
                        kv("duration_ms", (System.nanoTime() - start) / 1_000_000), kv("user_id", req.getAttribute(AuthFilter.USER_ATTR)));
            }
        }
    }
}
