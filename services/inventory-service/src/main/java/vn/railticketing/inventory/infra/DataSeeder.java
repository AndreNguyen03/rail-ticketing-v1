package vn.railticketing.inventory.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import vn.railticketing.inventory.client.ScheduleClient;
import vn.railticketing.inventory.client.dto.ScheduleTripSummary;

import java.time.LocalDate;
import java.util.ArrayList;
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

    // Seed 49 SE1 days: enough domains for multi-trip k6.
    private static final LocalDate SE1_START = LocalDate.of(2026, 2, 14);
    private static final int       SE1_DAYS  = 49;

    @Override
    public void run(String... args) {
        List<Long> tripIds = new ArrayList<>();

        // SE1 HN→SG: 49 consecutive days from V2.
        for (int d = 0; d < SE1_DAYS; d++) {
            String date = SE1_START.plusDays(d).toString();
            try {
                scheduleClient.searchTrips("HN", "SG", date).trips()
                        .stream().map(ScheduleTripSummary::tripId).forEach(tripIds::add);
            } catch (RestClientException e) {
                log.warn("DataSeeder: could not fetch HN→SG trips for {} — {}", date, e.getMessage());
            }
        }

        // SE2 SG→HN: base date only.
        try {
            scheduleClient.searchTrips("SG", "HN", "2026-02-14").trips()
                    .stream().map(ScheduleTripSummary::tripId).forEach(tripIds::add);
        } catch (RestClientException e) {
            log.warn("DataSeeder: could not fetch SG→HN trips — {}", e.getMessage());
        }

        List<Long> distinct = tripIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            log.warn("DataSeeder: no trips found in schedule-service — berth_inventory not seeded");
            return;
        }

        for (Long tripId : distinct) {
            // Via Spring proxy so @Transactional applies.
            seedService.seedTrip(tripId);
        }
    }
}
