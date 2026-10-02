package com.seatreservation.controller;

import com.seatreservation.model.dto.ReserveSeatsRequest;
import com.seatreservation.model.dto.ReservationResponse;
import com.seatreservation.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserveSeats(
            @PathVariable String showId,
            @Valid @RequestBody ReserveSeatsRequest request,
            Authentication authentication) {

        String userId = authentication.getName();
        ReservationResponse response = reservationService.reserveSeats(showId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    public ResponseEntity<Void> cancelReservation(
            @PathVariable String reservationId,
            Authentication authentication) {

        String userId = authentication.getName();
        reservationService.cancelReservation(reservationId, userId);
        return ResponseEntity.noContent().build();
    }
}
