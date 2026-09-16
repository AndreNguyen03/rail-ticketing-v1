package vn.railticketing.inventory.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import vn.railticketing.inventory.client.ScheduleClient;
import vn.railticketing.inventory.client.dto.ScheduleTripDetail;
import vn.railticketing.inventory.domain.BerthInventory;
import vn.railticketing.inventory.repository.BerthInventoryRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BerthInventorySeedService {

    private static final Logger log = LoggerFactory.getLogger(BerthInventorySeedService.class);

    private final ScheduleClient scheduleClient;
    private final BerthInventoryRepository berthInventoryRepository;
    private final StringRedisTemplate redisTemplate;
    private final boolean redisEnabled;

    public BerthInventorySeedService(ScheduleClient scheduleClient,
                                     BerthInventoryRepository berthInventoryRepository,
                                     @Autowired(required = false)
                                     StringRedisTemplate redisTemplate,
                                     @Value("${inventory.redis.enabled:false}")
                                     boolean redisEnabled) {
        this.scheduleClient = scheduleClient;
        this.berthInventoryRepository = berthInventoryRepository;
        this.redisTemplate = redisTemplate;
        this.redisEnabled = redisEnabled;
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
        List<BerthInventory> toSync = new ArrayList<>();
        for (var carriage : detail.carriages()) {
            for (var berth : carriage.berths()) {
                if (berthInventoryRepository.existsById(berth.berthId())) continue;
                BerthInventory bi = BerthInventory.seed(
                        berth.berthId(),
                        detail.tripId(),
                        (short) berth.carriageNo(),
                        (short) berth.berthNo(),
                        berth.berthClass(),
                        berth.level() != null ? berth.level().shortValue() : null,
                        berth.priceVnd()
                );
                berthInventoryRepository.save(bi);
                toSync.add(bi);
                seeded++;
            }
        }
        log.info("BerthInventorySeedService: trip {} — {} berths seeded", tripId, seeded);
        if (redisEnabled && redisTemplate != null && !toSync.isEmpty()) {
            try {
                // Group by berthClass for per-class hash keys inv:{tripId}:{class}
                var byClass = toSync.stream().collect(Collectors.groupingBy(BerthInventory::getBerthClass));
                for (var e : byClass.entrySet()) {
                    String key = "inv:{" + tripId + "}:" + e.getKey();
                    Map<String,String> map = new HashMap<>();
                    for (BerthInventory bi : e.getValue()) {
                        map.put(String.valueOf(bi.getBerthId()),
                                bi.getOccupiedMask() + "," + bi.getHeldMask() + "," +
                                (bi.getLevel()!=null?bi.getLevel():0) + "," + bi.getCarriageNo() + "," + bi.getBerthNo() + "," + bi.getPriceVnd());
                    }
                    redisTemplate.opsForHash().putAll(key, map);
                }
                log.info("BerthInventorySeedService: trip {} — synced {} berths to Redis", tripId, toSync.size());
            } catch (Exception ex) {
                log.warn("Redis sync failed for trip {}: {}", tripId, ex.getMessage());
            }
        }
    }
}
