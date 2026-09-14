package vn.railticketing.inventory.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import vn.railticketing.inventory.client.dto.ScheduleTripDetail;
import vn.railticketing.inventory.client.dto.TripsResponse;

@HttpExchange
public interface ScheduleClient {

    @GetExchange("/api/v1/trips")
    TripsResponse searchTrips(
            @RequestParam String fromStationCode,
            @RequestParam String toStationCode,
            @RequestParam String departureDate
    );

    @GetExchange("/api/v1/trips/{tripId}")
    ScheduleTripDetail getTrip(@PathVariable Long tripId);
}
