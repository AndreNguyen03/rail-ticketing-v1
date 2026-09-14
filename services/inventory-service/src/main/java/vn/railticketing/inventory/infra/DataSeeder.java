package vn.railticketing.inventory.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import vn.railticketing.inventory.client.ScheduleClient;
import vn.railticketing.inventory.client.dto.ScheduleTripSummary;
import vn.railticketing.inventory.client.dto.TripsResponse;

import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final ScheduleClient scheduleClient;
    private final BerthInventorySeedService seedService;

    public DataSeeder(ScheduleClient scheduleClient,
                      BerthInventorySeedService seedService) {
        this.scheduleClient = scheduleClient;
        this.seedService = seedService;
    }

    @Override
    public void run(String... args) {
        List<Long> tripIds;
        try {
            // Stage 0 has SE1 (HN→SG) and SE2 (SG→HN) on 2026-02-14.
            // Querying both directions to discover all trip IDs.
            TripsResponse hnSg = scheduleClient.searchTrips("HN", "SG", "2026-02-14");
            TripsResponse sgHn = scheduleClient.searchTrips("SG", "HN", "2026-02-14");
            tripIds = java.util.stream.Stream
                    .concat(hnSg.trips().stream(), sgHn.trips().stream())
                    .map(ScheduleTripSummary::tripId)
                    .distinct()
                    .toList();
        } catch (RestClientException e) {
            log.warn("DataSeeder: schedule-service unavailable — berth_inventory not seeded. " +
                     "Restart after schedule-service is up. Reason: {}", e.getMessage());
            return;
        }

        if (tripIds.isEmpty()) {
            log.warn("DataSeeder: no trips found in schedule-service");
            return;
        }

        for (Long tripId : tripIds) {
            // Goes through Spring proxy → @Transactional on seedService.seedTrip() is honoured
            seedService.seedTrip(tripId);
        }
    }
}
