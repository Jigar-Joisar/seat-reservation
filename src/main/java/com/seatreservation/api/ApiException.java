package com.seatreservation.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String error;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String error, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.error = error;
        this.details = details;
    }

    public ApiException(HttpStatus status, String error, String message) {
        this(status, error, message, Map.of());
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }

    public HttpStatus status() { return status; }
    public String error() { return error; }
    public Map<String, Object> details() { return details; }
}
