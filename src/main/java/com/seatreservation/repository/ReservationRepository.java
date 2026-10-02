package com.seatreservation.repository;

import com.seatreservation.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, String> {

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT COUNT(r) FROM Reservation r WHERE r.show.id = :showId AND r.userId = :userId AND r.status = 'CONFIRMED'")
    long countByShowIdAndUserIdAndStatus(@Param("showId") String showId,
                                        @Param("userId") String userId);
}
