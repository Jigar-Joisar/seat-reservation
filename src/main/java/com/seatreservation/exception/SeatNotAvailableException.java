package com.seatreservation.exception;

public class SeatNotAvailableException extends RuntimeException {
    private final String seatNumber;
    private final String currentStatus;

    public SeatNotAvailableException(String seatNumber, String currentStatus) {
        super(String.format("Seat %s is not available (current status: %s)", seatNumber, currentStatus));
        this.seatNumber = seatNumber;
        this.currentStatus = currentStatus;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }
}
