package com.seatreservation.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SeatNotAvailableException.class)
    public ResponseEntity<Map<String, Object>> handleSeatNotAvailable(SeatNotAvailableException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "seat_not_available");
        body.put("message", e.getMessage());
        body.put("details", Map.of(
            "seat", e.getSeatNumber(),
            "current_status", e.getCurrentStatus()
        ));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(PerUserLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handlePerUserLimitExceeded(PerUserLimitExceededException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "per_user_limit_exceeded");
        body.put("message", e.getMessage());
        body.put("details", Map.of(
            "limit", e.getLimit(),
            "current_count", e.getCurrentCount(),
            "requested_count", e.getRequestedCount()
        ));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(IdempotencyConflictException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "idempotency_conflict");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "invalid_request");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "internal_error");
        body.put("message", "An unexpected error occurred");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
