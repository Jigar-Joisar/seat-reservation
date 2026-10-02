package com.seatreservation.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "seats",
       uniqueConstraints = @UniqueConstraint(columnNames = {"show_id", "seat_number"}))
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @Column(nullable = false)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(name = "held_by_user_id")
    private String heldByUserId;

    @Column(name = "held_at")
    private Instant heldAt;

    @Column(name = "confirmed_by_user_id")
    private String confirmedByUserId;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "reservation_id")
    private String reservationId;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    public Seat() {
    }

    public Seat(String id, Show show, String seatNumber, SeatStatus status, String heldByUserId, Instant heldAt, String confirmedByUserId, Instant confirmedAt, String reservationId, Instant holdExpiresAt) {
        this.id = id;
        this.show = show;
        this.seatNumber = seatNumber;
        this.status = status;
        this.heldByUserId = heldByUserId;
        this.heldAt = heldAt;
        this.confirmedByUserId = confirmedByUserId;
        this.confirmedAt = confirmedAt;
        this.reservationId = reservationId;
        this.holdExpiresAt = holdExpiresAt;
    }

    public enum SeatStatus {
        AVAILABLE,
        HELD,
        CONFIRMED
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Show getShow() {
        return show;
    }

    public void setShow(Show show) {
        this.show = show;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public void setSeatNumber(String seatNumber) {
        this.seatNumber = seatNumber;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public void setStatus(SeatStatus status) {
        this.status = status;
    }

    public String getHeldByUserId() {
        return heldByUserId;
    }

    public void setHeldByUserId(String heldByUserId) {
        this.heldByUserId = heldByUserId;
    }

    public Instant getHeldAt() {
        return heldAt;
    }

    public void setHeldAt(Instant heldAt) {
        this.heldAt = heldAt;
    }

    public String getConfirmedByUserId() {
        return confirmedByUserId;
    }

    public void setConfirmedByUserId(String confirmedByUserId) {
        this.confirmedByUserId = confirmedByUserId;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public void setHoldExpiresAt(Instant holdExpiresAt) {
        this.holdExpiresAt = holdExpiresAt;
    }
}
