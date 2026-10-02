package com.seatreservation.controller;

import com.seatreservation.api.Dtos.*;
import com.seatreservation.filter.AuthFilter;
import com.seatreservation.service.ReservationService;
import com.seatreservation.service.ShowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ShowController {
    private final ShowService shows;
    private final ReservationService reservations;

    public ShowController(ShowService shows, ReservationService reservations) {
        this.shows = shows;
        this.reservations = reservations;
    }

    @PostMapping("/shows")
    public ResponseEntity<ShowView> create(@RequestBody CreateShowRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shows.create(req));
    }

    @GetMapping("/shows/{id}")
    public ShowView get(@PathVariable String id) {
        return shows.get(id);
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationView> reserve(@PathVariable String id, @RequestBody ReserveRequest req,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
                                                   @RequestAttribute(AuthFilter.USER_ATTR) String userId) {
        return place(id, req, headerKey, userId, false);
    }

    @PostMapping("/shows/{id}/hold")
    public ResponseEntity<ReservationView> hold(@PathVariable String id, @RequestBody ReserveRequest req,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
                                                @RequestAttribute(AuthFilter.USER_ATTR) String userId) {
        return place(id, req, headerKey, userId, true);
    }

    private ResponseEntity<ReservationView> place(String id, ReserveRequest req, String headerKey, String userId, boolean hold) {
        String key = req.idempotencyKey();
        if (headerKey != null && key != null && !headerKey.equals(key)) {
            throw com.seatreservation.api.ApiException.badRequest("Idempotency-Key header and body idempotency_key differ");
        }
        if (key == null) key = headerKey;
        ReservationService.Result r = reservations.reserve(id, userId, req.seats(), key, hold);
        return r.replay()
                ? ResponseEntity.ok().header("Idempotent-Replay", "true").body(r.view())
                : ResponseEntity.status(HttpStatus.CREATED).body(r.view());
    }

    @PostMapping("/reservations/{id}/confirm")
    public ReservationView confirm(@PathVariable String id, @RequestAttribute(AuthFilter.USER_ATTR) String userId) {
        return reservations.confirm(id, userId);
    }

    @GetMapping("/reservations/{id}")
    public ReservationView getReservation(@PathVariable String id, @RequestAttribute(AuthFilter.USER_ATTR) String userId) {
        return reservations.get(id, userId);
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationView cancel(@PathVariable String id, @RequestAttribute(AuthFilter.USER_ATTR) String userId) {
        return reservations.cancel(id, userId);
    }
}
