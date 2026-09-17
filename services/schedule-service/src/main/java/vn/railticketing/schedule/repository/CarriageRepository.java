package vn.railticketing.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.railticketing.schedule.domain.Carriage;

import java.util.List;

public interface CarriageRepository extends JpaRepository<Carriage, Long> {

    /** Load carriages + berths in 1 query: avoid N+1 per carriage. */
    @Query("""
            SELECT DISTINCT c FROM Carriage c
            LEFT JOIN FETCH c.berths
            WHERE c.trip.tripId = :tripId
            """)
    List<Carriage> findAllWithBerthsByTripId(@Param("tripId") Long tripId);
}
