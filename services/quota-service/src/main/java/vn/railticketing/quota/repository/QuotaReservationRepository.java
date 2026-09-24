package vn.railticketing.quota.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.railticketing.quota.domain.QuotaReservation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuotaReservationRepository extends JpaRepository<QuotaReservation, UUID> {

    List<QuotaReservation> findByBookingIdAndStatus(UUID bookingId, String status);

    Optional<QuotaReservation> findByBookingIdAndPassengerIdNumberAndStatus(
            UUID bookingId, String passengerIdNumber, String status);

    @Query("""
        SELECT r.passengerIdNumber, r.fromStationIndex, r.toStationIndex, SUM(r.ticketCount)
        FROM QuotaReservation r
        WHERE r.saleWindow.saleWindowId = :windowId AND r.status = 'ACTIVE'
        GROUP BY r.passengerIdNumber, r.fromStationIndex, r.toStationIndex
        """)
    List<Object[]> sumActiveByWindow(@Param("windowId") Long windowId);

    @Query("""
        SELECT COALESCE(SUM(r.ticketCount), 0)
        FROM QuotaReservation r
        WHERE r.saleWindow.saleWindowId = :windowId
          AND r.passengerIdNumber = :idNumber
          AND r.fromStationIndex  = :fromStation
          AND r.toStationIndex    = :toStation
          AND r.status = 'ACTIVE'
        """)
    int sumActiveTickets(@Param("windowId")    Long windowId,
                         @Param("idNumber")    String idNumber,
                         @Param("fromStation") short fromStation,
                         @Param("toStation")   short toStation);

    @Modifying
    @Query("""
        UPDATE QuotaReservation r SET r.status = 'RELEASED'
        WHERE r.bookingId = :bookingId AND r.status = 'ACTIVE'
        """)
    int releaseByBookingId(@Param("bookingId") UUID bookingId);
}
