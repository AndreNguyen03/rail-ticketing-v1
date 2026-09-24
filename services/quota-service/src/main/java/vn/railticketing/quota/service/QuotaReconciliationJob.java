package vn.railticketing.quota.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.quota.domain.SaleWindow;
import vn.railticketing.quota.repository.QuotaReservationRepository;
import vn.railticketing.quota.repository.SaleWindowRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

// Repairs Redis drift by recomputing counters from PostgreSQL (source of truth).
// Runs inside quota-service only — no cross-service calls.
@Component
public class QuotaReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(QuotaReconciliationJob.class);

    private final SaleWindowRepository       windowRepo;
    private final QuotaReservationRepository reservationRepo;
    private final RedisTemplate<String, String> redis;

    @Value("${quota.redis.enabled:false}")
    private boolean redisEnabled;

    public QuotaReconciliationJob(SaleWindowRepository windowRepo,
                                  QuotaReservationRepository reservationRepo,
                                  RedisTemplate<String, String> redis) {
        this.windowRepo      = windowRepo;
        this.reservationRepo = reservationRepo;
        this.redis           = redis;
    }

    @Scheduled(fixedDelayString = "${quota.reconciliation.interval-ms:60000}")
    @Transactional(readOnly = true)
    public void reconcile() {
        if (!redisEnabled) return;

        Optional<SaleWindow> windowOpt = windowRepo.findActiveAt(Instant.now());
        if (windowOpt.isEmpty()) return;

        SaleWindow window = windowOpt.get();
        long remainingTtl = Math.max(1, window.getEndsAt().getEpochSecond() - Instant.now().getEpochSecond());

        // Query DB: SUM per (passenger, fromStation, toStation)
        var rows = reservationRepo.sumActiveByWindow(window.getSaleWindowId());

        int checked = 0, fixed = 0;
        for (Object[] row : rows) {
            String idNumber    = (String) row[0];
            short  fromStation = ((Number) row[1]).shortValue();
            short  toStation   = ((Number) row[2]).shortValue();
            long   dbCount     = ((Number) row[3]).longValue();

            String key = QuotaService.quotaKey(window.getSaleWindowId(), fromStation, toStation, idNumber);
            String redisVal = redis.opsForValue().get(key);
            long   redisCount = redisVal == null ? 0L : Long.parseLong(redisVal);

            checked++;
            if (redisCount != dbCount) {
                redis.opsForValue().set(key, String.valueOf(dbCount), remainingTtl, TimeUnit.SECONDS);
                log.warn("quota reconciliation: fixed key={} redis={} db={}", key, redisCount, dbCount);
                fixed++;
            }
        }

        if (fixed > 0) {
            log.info("quota reconciliation: window={} checked={} fixed={}", window.getSaleWindowId(), checked, fixed);
        } else {
            log.debug("quota reconciliation: window={} checked={} — no drift", window.getSaleWindowId(), checked);
        }
    }
}
