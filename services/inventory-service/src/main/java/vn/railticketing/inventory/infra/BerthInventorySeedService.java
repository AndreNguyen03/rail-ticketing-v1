package vn.railticketing.inventory.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import vn.railticketing.inventory.client.ScheduleClient;
import vn.railticketing.inventory.client.dto.ScheduleTripDetail;
import vn.railticketing.inventory.domain.BerthInventory;
import vn.railticketing.inventory.repository.BerthInventoryRepository;

@Service
public class BerthInventorySeedService {

    private static final Logger log = LoggerFactory.getLogger(BerthInventorySeedService.class);

    private final ScheduleClient scheduleClient;
    private final BerthInventoryRepository berthInventoryRepository;

    public BerthInventorySeedService(ScheduleClient scheduleClient,
                                     BerthInventoryRepository berthInventoryRepository) {
        this.scheduleClient = scheduleClient;
        this.berthInventoryRepository = berthInventoryRepository;
    }

    @Transactional(
            readOnly    = false,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            rollbackFor = Exception.class
    )
    public void seedTrip(Long tripId) {
        ScheduleTripDetail detail;
        try {
            detail = scheduleClient.getTrip(tripId);
        } catch (RestClientException e) {
            log.warn("BerthInventorySeedService: could not fetch trip {} — skipping. Reason: {}",
                    tripId, e.getMessage());
            return;
        }

        int seeded = 0;
        for (var carriage : detail.carriages()) {
            for (var berth : carriage.berths()) {
                if (berthInventoryRepository.existsById(berth.berthId())) continue;
                berthInventoryRepository.save(BerthInventory.seed(
                        berth.berthId(),
                        detail.tripId(),
                        (short) berth.carriageNo(),
                        (short) berth.berthNo(),
                        berth.berthClass(),
                        berth.level() != null ? berth.level().shortValue() : null,
                        berth.priceVnd()
                ));
                seeded++;
            }
        }
        log.info("BerthInventorySeedService: trip {} — {} berths seeded", tripId, seeded);
    }
}
