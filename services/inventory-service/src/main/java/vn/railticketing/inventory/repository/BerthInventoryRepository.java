package vn.railticketing.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.railticketing.inventory.domain.BerthInventory;

import java.util.List;

public interface BerthInventoryRepository extends JpaRepository<BerthInventory, Long> {

    // ── Availability (read-only, no lock) ────────────────────────────────────

    // JPQL does not support bitwise operators — using native SQL for the bitmask check.
    @Query(value = """
            SELECT * FROM berth_inventory
            WHERE trip_id        = :tripId
              AND berth_class    = COALESCE(:berthClass, berth_class)
              AND (occupied_mask | held_mask) & :journeyMask = 0
            """, nativeQuery = true)
    List<BerthInventory> findAvailableForJourney(
            @Param("tripId") Long tripId,
            @Param("journeyMask") int journeyMask,
            @Param("berthClass") String berthClass
    );

    // ── Lock for hold creation (pessimistic, SKIP LOCKED) ────────────────────

    // @Lock is ignored with nativeQuery=true; FOR UPDATE SKIP LOCKED is in the SQL.
    // SKIP LOCKED: competing requests skip locked rows and move to the next free berth
    // instead of queuing — prevents the service from hanging under high contention.
    @Query(value = """
            SELECT * FROM berth_inventory
            WHERE trip_id        = :tripId
              AND berth_class    = COALESCE(:berthClass, berth_class)
              AND (occupied_mask | held_mask) & :journeyMask = 0
            LIMIT :limitVal
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<BerthInventory> lockAvailableForJourney(
            @Param("tripId") Long tripId,
            @Param("journeyMask") int journeyMask,
            @Param("berthClass") String berthClass,
            @Param("limitVal") int limitVal
    );

    List<BerthInventory> findAllByBerthIdIn(List<Long> berthIds);

    @Query("SELECT b FROM BerthInventory b WHERE b.tripId = :tripId AND b.berthClass = :berthClass")
    List<BerthInventory> findByTripIdAndBerthClass(
            @Param("tripId") Long tripId,
            @Param("berthClass") String berthClass
    );
}
