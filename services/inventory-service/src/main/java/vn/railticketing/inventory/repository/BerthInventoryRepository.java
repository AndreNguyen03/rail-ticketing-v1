package vn.railticketing.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.railticketing.inventory.domain.BerthInventory;

import java.util.List;

public interface BerthInventoryRepository extends JpaRepository<BerthInventory, Long> {

    // Free-berth read: no lock.

    // JPQL lacks bitwise ops: native SQL for mask check.
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

    // Hold creation lock: pessimistic SKIP LOCKED.

    // @Lock ignored with nativeQuery: SKIP LOCKED in SQL lets contenders skip rows, no hang.
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
