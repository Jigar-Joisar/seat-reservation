package com.seatreservation.service;

import com.seatreservation.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class HoldExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryScheduler.class);

    private final SeatRepository seatRepository;

    public HoldExpiryScheduler(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    @Scheduled(fixedRate = 30000) // Every 30 seconds
    public void expireHolds() {
        Instant now = Instant.now();
        int expiredCount = seatRepository.expireHeldSeats(now);
        if (expiredCount > 0) {
            log.info("Expired {} held seats", expiredCount);
        }
    }
}
