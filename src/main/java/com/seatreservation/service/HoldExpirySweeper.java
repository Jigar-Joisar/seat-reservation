package com.seatreservation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HoldExpirySweeper {
    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);
    private final ReservationService service;

    public HoldExpirySweeper(ReservationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.hold-sweep-ms}")
    public void sweep() {
        try {
            for (var h : service.overdueHolds(500)) service.expireIfOverdue(h[0], h[1], h[2]);
        } catch (RuntimeException e) {
            log.warn("hold sweep failed, will retry", e);
        }
    }
}
