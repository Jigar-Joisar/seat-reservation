package com.seatreservation.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

/** In-process hints. Bounded by size and age; both evictions only lose an optimisation. */
public class LocalSeatHints implements SeatHints {
    private final Cache<String, Boolean> taken;
    private final AtomicLong epoch = new AtomicLong();
    private final Counter hit, miss, learned, stale, released;

    public LocalSeatHints(MeterRegistry registry, long maxEntries, Duration ttl) {
        this.taken = Caffeine.newBuilder().maximumSize(maxEntries).expireAfterWrite(ttl).build();
        this.hit = counter(registry, "hit");
        this.miss = counter(registry, "miss");
        this.learned = counter(registry, "learned");
        this.stale = counter(registry, "dropped_stale");
        this.released = counter(registry, "released");
        Gauge.builder("seat.hints.size", taken, c -> c.estimatedSize()).register(registry);
    }

    private static Counter counter(MeterRegistry r, String event) {
        return Counter.builder("seat.hints").tag("event", event).register(r);
    }

    private static String key(String showId, String seat) { return showId + '|' + seat; }

    public boolean enabled() { return true; }

    public long epoch() { return epoch.get(); }

    public boolean isTaken(String showId, String seat) {
        boolean t = taken.getIfPresent(key(showId, seat)) != null;
        (t ? hit : miss).increment();
        return t;
    }

    public void learnTaken(String showId, Collection<String> seats, long observedEpoch) {
        if (epoch.get() != observedEpoch) { stale.increment(); return; }
        for (String s : seats) taken.put(key(showId, s), Boolean.TRUE);
        learned.increment(seats.size());
    }

    /** Bump first, remove second: see the race note on {@link SeatHints}. */
    public void released(String showId, Collection<String> seats) {
        epoch.incrementAndGet();
        for (String s : seats) taken.invalidate(key(showId, s));
        released.increment(seats.size());
    }

    public void clear() { epoch.incrementAndGet(); taken.invalidateAll(); }

    public long size() { taken.cleanUp(); return taken.estimatedSize(); }
}
