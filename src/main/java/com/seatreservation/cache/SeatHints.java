package com.seatreservation.cache;

import java.util.Collection;

/**
 * Advisory "this seat is taken" hints. A hint can only make a request fail early with seat_taken; it can never grant
 * a booking, so an empty, evicted or restarted store is always safe: the database decides, exactly as without hints.
 *
 * Race protection: a writer reads {@link #epoch()} before it looks at the database and passes it to
 * {@link #learnTaken}. Every release bumps the epoch before removing its entries, so a hint learned from a read that
 * could predate a release is dropped instead of outliving the release.
 */
public interface SeatHints {
    boolean enabled();

    long epoch();

    boolean isTaken(String showId, String seat);

    void learnTaken(String showId, Collection<String> seats, long epoch);

    void released(String showId, Collection<String> seats);

    void clear();

    long size();
}
