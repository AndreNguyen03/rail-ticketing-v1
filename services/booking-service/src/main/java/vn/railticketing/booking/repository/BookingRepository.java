package vn.railticketing.booking.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.railticketing.booking.domain.Booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByIdempotencyKey(UUID idempotencyKey);

    @Query("SELECT DISTINCT b FROM Booking b LEFT JOIN FETCH b.tickets WHERE b.idempotencyKey = :key")
    Optional<Booking> findByIdempotencyKeyWithTickets(@Param("key") UUID idempotencyKey);

    @Query("SELECT DISTINCT b FROM Booking b LEFT JOIN FETCH b.tickets WHERE b.bookingId = :id")
    Optional<Booking> findByIdWithTickets(@Param("id") UUID id);

    @Query("SELECT b FROM Booking b WHERE b.status = 'PENDING_PAYMENT' AND b.expiresAt < :now")
    List<Booking> findExpiredPending(@Param("now") Instant now);
}
