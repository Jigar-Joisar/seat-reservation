package com.seatreservation.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public class ReserveSeatsRequest {

    @NotEmpty(message = "At least one seat is required")
    private List<String> seats;

    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;

    public ReserveSeatsRequest() {
    }

    public ReserveSeatsRequest(List<String> seats, String idempotencyKey) {
        this.seats = seats;
        this.idempotencyKey = idempotencyKey;
    }

    public List<String> getSeats() {
        return seats;
    }

    public void setSeats(List<String> seats) {
        this.seats = seats;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
