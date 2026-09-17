package vn.railticketing.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.railticketing.schedule.domain.Trip;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    /** Find trips in travel direction: double-join trip_stop, filter fromIndex < toIndex. Returns [Trip, from, to]. */
    @Query("""
            SELECT t, fromStop, toStop
            FROM Trip t
            JOIN t.stops fromStop
            JOIN t.stops toStop
            WHERE t.serviceDate = :date
              AND fromStop.stationCode = :fromCode
              AND toStop.stationCode   = :toCode
              AND fromStop.id.stationIndex < toStop.id.stationIndex
              AND t.status IN ('SCHEDULED', 'SELLING')
            """)
    List<Object[]> searchTrips(
            @Param("fromCode") String fromCode,
            @Param("toCode") String toCode,
            @Param("date") LocalDate date
    );

    /** Load trip + stops + station names in 1 query: DISTINCT avoids duplicate Trip. */
    @Query("""
            SELECT DISTINCT t FROM Trip t
            LEFT JOIN FETCH t.stops s
            LEFT JOIN FETCH s.station
            WHERE t.tripId = :tripId
            """)
    Optional<Trip> findByIdWithStops(@Param("tripId") Long tripId);
}
