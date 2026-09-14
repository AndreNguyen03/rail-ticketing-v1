package vn.railticketing.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.railticketing.schedule.domain.Carriage;

import java.util.List;

public interface CarriageRepository extends JpaRepository<Carriage, Long> {

    /**
     * Load all carriages for a trip with berths in a single query.
     * Avoids N+1: without this, each carriage.getBerths() call fires a separate query.
     */
    @Query("""
            SELECT DISTINCT c FROM Carriage c
            LEFT JOIN FETCH c.berths
            WHERE c.trip.tripId = :tripId
            """)
    List<Carriage> findAllWithBerthsByTripId(@Param("tripId") Long tripId);
}
