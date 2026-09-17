package vn.railticketing.inventory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.railticketing.inventory.domain.Hold;
import vn.railticketing.inventory.repository.HoldRepository;

import java.time.Instant;
import java.util.List;

@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final HoldRepository holdRepository;
    private final HoldService holdService;

    public HoldExpiryJob(HoldRepository holdRepository, HoldService holdService) {
        this.holdRepository = holdRepository;
        this.holdService = holdService;
    }

    // Interval from yml: tunable without rebuild.
    @Scheduled(fixedDelayString = "${inventory.hold.sweep-interval-ms}")
    public void sweep() {
        List<Hold> expired = holdRepository.findExpiredWithBerths(Instant.now());
        if (expired.isEmpty()) return;

        log.info("HoldExpiryJob: releasing {} expired hold(s)", expired.size());

        // One transaction per hold: one failure does not roll back the rest.
        for (Hold hold : expired) {
            try {
                holdService.expireHold(hold);
            } catch (Exception e) {
                log.error("HoldExpiryJob: failed to expire hold {}: {}", hold.getHoldId(), e.getMessage());
            }
        }
    }
}
