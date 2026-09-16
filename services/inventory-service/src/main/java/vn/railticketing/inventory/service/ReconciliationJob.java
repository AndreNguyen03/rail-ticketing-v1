package vn.railticketing.inventory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.inventory.domain.BerthInventory;
import vn.railticketing.inventory.repository.BerthInventoryRepository;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stage 4 — Redis ↔ PostgreSQL drift repair.
 *
 * Runs every 60s. For each inventory key in Redis, compares held_mask against DB.
 * DB is the source of truth. Any Redis berth whose held bits differ from DB is
 * corrected in-place via HSET.
 *
 * Drift sources:
 *   - Redis killed mid-hold (hold key gone, inventory hash still dirty)
 *   - Hold expired in DB (HoldExpiryJob) but release.lua never ran
 *   - Network partition caused split-brain writes
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    @Value("${inventory.redis.enabled:false}")
    private boolean enabled;

    private final StringRedisTemplate redis;
    private final BerthInventoryRepository repo;

    public ReconciliationJob(@Autowired(required = false) StringRedisTemplate redis,
                             BerthInventoryRepository repo) {
        this.redis = redis;
        this.repo  = repo;
    }

    @Scheduled(fixedDelayString = "${inventory.reconciliation.interval-ms:60000}")
    @Transactional(readOnly = true)
    public void reconcile() {
        if (!enabled || redis == null) return;

        Set<String> keys = redis.keys("inv:*");
        if (keys == null || keys.isEmpty()) {
            log.info("ReconciliationJob: no inventory keys in Redis");
            return;
        }

        int checked = 0, drifted = 0, fixed = 0;

        for (String key : keys) {
            // key format: inv:{tripId}:CLASS  e.g. inv:{1}:BERTH_4
            String[] parts = key.split(":");
            if (parts.length < 3) continue;

            long tripId;
            String berthClass;
            try {
                tripId    = Long.parseLong(parts[1].replaceAll("[{}]", ""));
                berthClass = parts[2];
            } catch (NumberFormatException e) {
                continue;
            }

            Map<Object, Object> redisEntries = redis.opsForHash().entries(key);
            if (redisEntries.isEmpty()) continue;

            // Build berthId → dbHeldMask lookup from DB (source of truth)
            Map<Long, Integer> dbHeld = repo.findByTripIdAndBerthClass(tripId, berthClass)
                    .stream()
                    .collect(Collectors.toMap(BerthInventory::getBerthId, BerthInventory::getHeldMask));

            for (Map.Entry<Object, Object> e : redisEntries.entrySet()) {
                Long berthId;
                try { berthId = Long.parseLong(e.getKey().toString()); }
                catch (NumberFormatException ex) { continue; }

                String val = e.getValue().toString();
                String[] fields = val.split(",");
                if (fields.length < 6) continue;

                int redisHeld;
                try { redisHeld = Integer.parseInt(fields[1]); }
                catch (NumberFormatException ex) { continue; }

                Integer dbHeldMask = dbHeld.get(berthId);
                if (dbHeldMask == null) continue;

                checked++;
                if (redisHeld != dbHeldMask) {
                    drifted++;
                    fields[1] = String.valueOf(dbHeldMask);
                    redis.opsForHash().put(key, e.getKey(), String.join(",", fields));
                    fixed++;
                }
            }
        }

        if (drifted > 0) {
            log.warn("ReconciliationJob: {} keys scanned, {} berths drifted, {} fixed",
                    keys.size(), drifted, fixed);
        } else {
            log.info("ReconciliationJob: {} keys scanned, {} berths checked — no drift",
                    keys.size(), checked);
        }
    }
}
