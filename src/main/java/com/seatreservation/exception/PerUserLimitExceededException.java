package com.seatreservation.exception;

public class PerUserLimitExceededException extends RuntimeException {
    private final int limit;
    private final long currentCount;
    private final int requestedCount;

    public PerUserLimitExceededException(int limit, long currentCount, int requestedCount) {
        super(String.format("Per-user limit exceeded: %d + %d requested > %d limit", currentCount, requestedCount, limit));
        this.limit = limit;
        this.currentCount = currentCount;
        this.requestedCount = requestedCount;
    }

    public int getLimit() {
        return limit;
    }

    public long getCurrentCount() {
        return currentCount;
    }

    public int getRequestedCount() {
        return requestedCount;
    }
}
