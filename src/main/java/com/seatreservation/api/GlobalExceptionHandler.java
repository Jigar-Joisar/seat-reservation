package com.seatreservation.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message, Map<String, Object> details) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error);
        m.put("message", message);
        if (details != null && !details.isEmpty()) m.put("details", details);
        return ResponseEntity.status(status).body(m);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.status(), e.error(), e.getMessage(), e.details());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    public ResponseEntity<Map<String, Object>> unreadable(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_request", "Malformed or missing request data", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> method(Exception e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", e.getMessage(), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(Exception e) {
        return body(HttpStatus.NOT_FOUND, "not_found", "Resource not found", null);
    }

    @ExceptionHandler({CannotAcquireLockException.class, ConcurrencyFailureException.class, QueryTimeoutException.class})
    public ResponseEntity<Map<String, Object>> contention(Exception e, HttpServletRequest req) {
        log.warn("lock contention declined request: {}", e.getClass().getSimpleName());
        return body(HttpStatus.CONFLICT, "contention", "Too much contention, retry the request with the same idempotency key", Map.of("retryable", true));
    }

    @ExceptionHandler({CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class,
            DataAccessResourceFailureException.class, TransactionSystemException.class})
    public ResponseEntity<Map<String, Object>> databaseUnavailable(Exception e) {
        log.warn("database unavailable, failing request closed: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                .body(Map.of("error", "service_unavailable", "message", "The database is unavailable; nothing was booked. Retry with the same idempotency key.", "details", Map.of("retryable", true)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        log.error("unhandled error: {}", e.getClass().getName(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred", null);
    }
}
