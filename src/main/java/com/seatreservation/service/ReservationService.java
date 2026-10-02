package com.seatreservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatreservation.exception.IdempotencyConflictException;
import com.seatreservation.exception.PerUserLimitExceededException;
import com.seatreservation.exception.SeatNotAvailableException;
import com.seatreservation.model.Reservation;
import com.seatreservation.model.Seat;
import com.seatreservation.model.Show;
import com.seatreservation.model.dto.ReserveSeatsRequest;
import com.seatreservation.model.dto.ReservationResponse;
import com.seatreservation.repository.ReservationRepository;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.repository.ShowRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final ShowRepository showRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ReservationService(ReservationRepository reservationRepository,
                             SeatRepository seatRepository,
                             ShowRepository showRepository,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry) {
        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;
        this.showRepository = showRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Value("${app.reservation.per-user-limit:4}")
    private int perUserLimit;

    @Value("${app.reservation.hold-duration-minutes:5}")
    private int holdDurationMinutes;

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ReservationResponse reserveSeats(String showId, ReserveSeatsRequest request, String userId) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Check idempotency first
            Optional<Reservation> existing = reservationRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing.isPresent()) {
                Reservation existingRes = existing.get();
                if (!seatListsMatch(request.getSeats(), existingRes.getSeats())) {
                    meterRegistry.counter("reservations_declined_total", "reason", "idempotent_replay").increment();
                    throw new IdempotencyConflictException("Same idempotency key with different seats");
                }
                log.info("Returning existing reservation for idempotency key: {}", request.getIdempotencyKey());
                return buildReservationResponse(existingRes);
            }

            // Load show
            Show show = showRepository.findById(showId)
                    .orElseThrow(() -> new IllegalArgumentException("Show not found: " + showId));

            // Sort seats in deterministic order to prevent deadlocks
            List<String> sortedSeats = request.getSeats().stream().sorted().toList();

            // Check per-user limit
            long currentHeldCount = reservationRepository.countByShowIdAndUserIdAndStatus(
                    showId, userId);
            if (currentHeldCount + sortedSeats.size() > perUserLimit) {
                meterRegistry.counter("reservations_declined_total", "reason", "per_user_limit").increment();
                throw new PerUserLimitExceededException(perUserLimit, currentHeldCount, sortedSeats.size());
            }

            // Lock seats using pessimistic locking
            List<Seat> seats = seatRepository.findByShowIdAndSeatNumberInWithLock(showId, sortedSeats);

            if (seats.size() != sortedSeats.size()) {
                meterRegistry.counter("reservations_declined_total", "reason", "seat_not_found").increment();
                throw new IllegalArgumentException("One or more seats not found");
            }

            // Check if all seats are available
            for (Seat seat : seats) {
                if (seat.getStatus() != Seat.SeatStatus.AVAILABLE) {
                    meterRegistry.counter("reservations_declined_total", "reason", "seat_taken").increment();
                    throw new SeatNotAvailableException(seat.getSeatNumber(), seat.getStatus().name());
                }
            }

            // Update all seats atomically
            Instant holdExpiry = Instant.now().plusSeconds(holdDurationMinutes * 60L);
            for (Seat seat : seats) {
                seat.setStatus(Seat.SeatStatus.CONFIRMED);
                seat.setConfirmedByUserId(userId);
                seat.setConfirmedAt(Instant.now());
            }
            seatRepository.saveAll(seats);

            // Create reservation
            String seatsJson;
            try {
                seatsJson = objectMapper.writeValueAsString(request.getSeats());
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize seats", e);
            }

            Reservation reservation = new Reservation();
            reservation.setShow(show);
            reservation.setUserId(userId);
            reservation.setSeats(seatsJson);
            reservation.setAmountPaise(show.getPricePaise() * request.getSeats().size());
            reservation.setStatus(Reservation.ReservationStatus.CONFIRMED);
            reservation.setIdempotencyKey(request.getIdempotencyKey());

            try {
                reservation = reservationRepository.save(reservation);
                meterRegistry.counter("reservations_confirmed_total").increment();
                log.info("Reservation created: {} for user: {}, seats: {}", reservation.getId(), userId, request.getSeats());
                return buildReservationResponse(reservation);
            } catch (DataIntegrityViolationException e) {
                // Unique constraint violation - another transaction won
                meterRegistry.counter("reservations_declined_total", "reason", "idempotency_conflict").increment();
                Optional<Reservation> winner = reservationRepository.findByIdempotencyKey(request.getIdempotencyKey());
                if (winner.isPresent()) {
                    if (!seatListsMatch(request.getSeats(), winner.get().getSeats())) {
                        throw new IdempotencyConflictException("Same idempotency key with different seats");
                    }
                    log.info("Another transaction won for idempotency key: {}", request.getIdempotencyKey());
                    return buildReservationResponse(winner.get());
                }
                throw e;
            }
        } finally {
            sample.stop(meterRegistry.timer("reservation_duration_seconds"));
        }
    }

    @Transactional
    public void cancelReservation(String reservationId, String userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found: " + reservationId));

        if (!reservation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("User can only cancel their own reservations");
        }

        if (reservation.getStatus() == Reservation.ReservationStatus.CANCELLED) {
            throw new IllegalArgumentException("Reservation already cancelled");
        }

        // Release seats
        // Note: In a more complex system, we'd track which seats belong to which reservation
        // For simplicity, we're assuming the reservation holds the seats mentioned in the seats JSON
        // In production, we'd have a reservation_seats junction table

        reservation.setStatus(Reservation.ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);

        meterRegistry.counter("reservations_cancelled_total").increment();
        log.info("Reservation cancelled: {} by user: {}", reservationId, userId);
    }

    private boolean seatListsMatch(List<String> requestSeats, String existingSeatsJson) {
        try {
            List<String> existingSeats = objectMapper.readValue(existingSeatsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return requestSeats.size() == existingSeats.size() &&
                   requestSeats.containsAll(existingSeats) &&
                   existingSeats.containsAll(requestSeats);
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private ReservationResponse buildReservationResponse(Reservation reservation) {
        try {
            List<String> seats = objectMapper.readValue(reservation.getSeats(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return new ReservationResponse(
                    reservation.getId(),
                    reservation.getShow().getId(),
                    reservation.getUserId(),
                    seats,
                    reservation.getAmountPaise(),
                    reservation.getStatus().name()
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize seats", e);
        }
    }
}
