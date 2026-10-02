package com.seatreservation.model.dto;

import java.util.List;

public class ShowResponse {

    private String id;
    private String name;
    private Long pricePaise;
    private Integer perUserLimit;
    private List<SeatStatusResponse> seats;
    private SeatCountsResponse counts;

    public ShowResponse() {
    }

    public ShowResponse(String id, String name, Long pricePaise, Integer perUserLimit, List<SeatStatusResponse> seats, SeatCountsResponse counts) {
        this.id = id;
        this.name = name;
        this.pricePaise = pricePaise;
        this.perUserLimit = perUserLimit;
        this.seats = seats;
        this.counts = counts;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getPricePaise() {
        return pricePaise;
    }

    public void setPricePaise(Long pricePaise) {
        this.pricePaise = pricePaise;
    }

    public Integer getPerUserLimit() {
        return perUserLimit;
    }

    public void setPerUserLimit(Integer perUserLimit) {
        this.perUserLimit = perUserLimit;
    }

    public List<SeatStatusResponse> getSeats() {
        return seats;
    }

    public void setSeats(List<SeatStatusResponse> seats) {
        this.seats = seats;
    }

    public SeatCountsResponse getCounts() {
        return counts;
    }

    public void setCounts(SeatCountsResponse counts) {
        this.counts = counts;
    }

    public static class SeatStatusResponse {
        private String seatNumber;
        private String status;

        public SeatStatusResponse() {
        }

        public SeatStatusResponse(String seatNumber, String status) {
            this.seatNumber = seatNumber;
            this.status = status;
        }

        public String getSeatNumber() {
            return seatNumber;
        }

        public void setSeatNumber(String seatNumber) {
            this.seatNumber = seatNumber;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public static class SeatCountsResponse {
        private long available;
        private long held;
        private long confirmed;
        private long total;

        public SeatCountsResponse() {
        }

        public SeatCountsResponse(long available, long held, long confirmed, long total) {
            this.available = available;
            this.held = held;
            this.confirmed = confirmed;
            this.total = total;
        }

        public long getAvailable() {
            return available;
        }

        public void setAvailable(long available) {
            this.available = available;
        }

        public long getHeld() {
            return held;
        }

        public void setHeld(long held) {
            this.held = held;
        }

        public long getConfirmed() {
            return confirmed;
        }

        public void setConfirmed(long confirmed) {
            this.confirmed = confirmed;
        }

        public long getTotal() {
            return total;
        }

        public void setTotal(long total) {
            this.total = total;
        }
    }
}
