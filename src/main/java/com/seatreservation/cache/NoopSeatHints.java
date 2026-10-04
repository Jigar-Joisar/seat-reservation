package com.seatreservation.cache;

import java.util.Collection;

public class NoopSeatHints implements SeatHints {
    public boolean enabled() { return false; }
    public long epoch() { return 0; }
    public boolean isTaken(String showId, String seat) { return false; }
    public void learnTaken(String showId, Collection<String> seats, long epoch) { }
    public void released(String showId, Collection<String> seats) { }
    public void clear() { }
    public long size() { return 0; }
}
