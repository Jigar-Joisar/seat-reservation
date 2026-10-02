package com.seatreservation.repository;

import com.seatreservation.model.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SeatRepository extends JpaRepository<Seat, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.show.id = :showId AND s.seatNumber IN :seatNumbers")
    List<Seat> findByShowIdAndSeatNumberInWithLock(@Param("showId") String showId,
                                                   @Param("seatNumbers") List<String> seatNumbers);

    @Modifying
    @Query("UPDATE Seat s SET s.status = 'AVAILABLE', s.heldByUserId = NULL, s.heldAt = NULL, s.holdExpiresAt = NULL WHERE s.status = 'HELD' AND s.holdExpiresAt < :now")
    int expireHeldSeats(@Param("now") Instant now);

    long countByShowIdAndStatus(String showId, Seat.SeatStatus status);

    List<Seat> findByShowId(String showId);
}
