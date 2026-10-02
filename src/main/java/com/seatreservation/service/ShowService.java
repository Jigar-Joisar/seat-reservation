package com.seatreservation.service;

import com.seatreservation.model.Show;
import com.seatreservation.model.Seat;
import com.seatreservation.model.dto.CreateShowRequest;
import com.seatreservation.model.dto.ShowResponse;
import com.seatreservation.repository.ShowRepository;
import com.seatreservation.repository.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ShowService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    public ShowService(ShowRepository showRepository, SeatRepository seatRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {
        Show show = new Show();
        show.setName(request.getName());
        show.setPricePaise(request.getPricePaise());

        Show savedShow = showRepository.save(show);

        List<Seat> seats = request.getSeats().stream()
                .map(seatNumber -> {
                    Seat seat = new Seat();
                    seat.setShow(savedShow);
                    seat.setSeatNumber(seatNumber);
                    seat.setStatus(Seat.SeatStatus.AVAILABLE);
                    return seat;
                })
                .collect(Collectors.toList());

        seatRepository.saveAll(seats);
        savedShow.setSeats(seats);

        return buildShowResponse(savedShow);
    }

    @Transactional(readOnly = true)
    public ShowResponse getShow(String showId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new IllegalArgumentException("Show not found: " + showId));

        return buildShowResponse(show);
    }

    private ShowResponse buildShowResponse(Show show) {
        List<ShowResponse.SeatStatusResponse> seatStatuses = show.getSeats().stream()
                .map(seat -> new ShowResponse.SeatStatusResponse(seat.getSeatNumber(), seat.getStatus().name()))
                .collect(Collectors.toList());

        long available = show.getSeats().stream()
                .filter(s -> s.getStatus() == Seat.SeatStatus.AVAILABLE)
                .count();
        long held = show.getSeats().stream()
                .filter(s -> s.getStatus() == Seat.SeatStatus.HELD)
                .count();
        long confirmed = show.getSeats().stream()
                .filter(s -> s.getStatus() == Seat.SeatStatus.CONFIRMED)
                .count();
        long total = show.getSeats().size();

        ShowResponse.SeatCountsResponse counts = new ShowResponse.SeatCountsResponse(available, held, confirmed, total);

        return new ShowResponse(show.getId(), show.getName(), show.getPricePaise(), show.getPerUserLimit(), seatStatuses, counts);
    }
}
