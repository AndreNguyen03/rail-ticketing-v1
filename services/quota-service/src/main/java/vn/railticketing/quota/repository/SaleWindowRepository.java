package vn.railticketing.quota.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.railticketing.quota.domain.SaleWindow;

import java.time.Instant;
import java.util.Optional;

public interface SaleWindowRepository extends JpaRepository<SaleWindow, Long> {

    @Query("SELECT w FROM SaleWindow w WHERE w.startsAt <= :now AND w.endsAt >= :now ORDER BY w.startsAt DESC LIMIT 1")
    Optional<SaleWindow> findActiveAt(@Param("now") Instant now);
}
