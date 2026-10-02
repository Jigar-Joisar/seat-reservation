package com.seatreservation.api;

import java.util.List;

public final class Dtos {
    private Dtos() {}

    public record CreateShowRequest(String name, List<String> seats, Long pricePaise, Integer perUserLimit) {}

    public record ReserveRequest(List<String> seats, String idempotencyKey) {}

    public record TokenRequest(String userId, String adminSecret) {}

    public record SeatView(String seat, String status) {}

    public record Counts(long available, long held, long confirmed, long totalSeats) {}

    public record ShowView(String id, String name, long pricePaise, int perUserLimit,
                           long available, long held, long confirmed, long totalSeats,
                           Counts counts, List<SeatView> seats) {}

    public record ReservationView(String reservationId, String showId, String userId, List<String> seats,
                                  long amountPaise, String status, java.time.Instant expiresAt) {}
}
