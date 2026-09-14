package vn.railticketing.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.railticketing.schedule.domain.Berth;

import java.util.Optional;

public interface BerthRepository extends JpaRepository<Berth, Long> {

    @Query("SELECT MIN(b.priceVnd) FROM Berth b WHERE b.tripId = :tripId")
    Optional<Long> findMinPriceVndByTripId(@Param("tripId") Long tripId);
}
