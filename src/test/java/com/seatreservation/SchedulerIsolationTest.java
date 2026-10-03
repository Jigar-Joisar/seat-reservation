package com.seatreservation;

import com.seatreservation.service.DbHealthMonitor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for a defect found in the live outage test: with Spring's default single-thread scheduler the hold sweeper
 * (blocked up to 60 s on a dead connection pool) starved DbHealthMonitor, so the circuit breaker opened ~66 s late.
 */
class SchedulerIsolationTest extends AbstractApiTest {
    @Autowired ThreadPoolTaskScheduler scheduler;
    @Autowired DbHealthMonitor monitor;

    @Test void scheduledJobsDoNotShareASingleThread() {
        assertTrue(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize() >= 2,
                "the sweeper and the DB health monitor need separate threads, pool size was " + scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
        assertTrue(monitor.isUp());
    }
}
