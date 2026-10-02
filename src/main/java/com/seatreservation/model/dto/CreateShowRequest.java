package com.seatreservation.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public class CreateShowRequest {

    @NotBlank(message = "Show name is required")
    private String name;

    @NotEmpty(message = "At least one seat is required")
    private List<String> seats;

    @Positive(message = "Price must be positive")
    private Long pricePaise;

    public CreateShowRequest() {
    }

    public CreateShowRequest(String name, List<String> seats, Long pricePaise) {
        this.name = name;
        this.seats = seats;
        this.pricePaise = pricePaise;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getSeats() {
        return seats;
    }

    public void setSeats(List<String> seats) {
        this.seats = seats;
    }

    public Long getPricePaise() {
        return pricePaise;
    }

    public void setPricePaise(Long pricePaise) {
        this.pricePaise = pricePaise;
    }
}
