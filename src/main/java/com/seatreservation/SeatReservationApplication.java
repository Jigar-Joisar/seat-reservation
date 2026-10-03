package com.seatreservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SeatReservationApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SeatReservationApplication.class);
        app.addInitializers(new com.seatreservation.config.PostgresUrlInitializer());
        app.run(args);
    }
}
