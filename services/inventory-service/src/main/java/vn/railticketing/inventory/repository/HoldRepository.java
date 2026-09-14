package vn.railticketing.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.railticketing.inventory.domain.Hold;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    Optional<Hold> findByIdempotencyKey(UUID idempotencyKey);

    // Fetch holds with their berths eagerly to avoid N+1 in the expiry sweep
    @Query("SELECT DISTINCT h FROM Hold h LEFT JOIN FETCH h.holdBerths WHERE h.expiresAt < :now")
    List<Hold> findExpiredWithBerths(@Param("now") Instant now);

    @Query("SELECT DISTINCT h FROM Hold h LEFT JOIN FETCH h.holdBerths WHERE h.holdId = :holdId")
    Optional<Hold> findByIdWithBerths(@Param("holdId") UUID holdId);
}
