package vn.railticketing.fare.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import vn.railticketing.fare.client.dto.TripDetailDto;

@HttpExchange
public interface ScheduleClient {

    @GetExchange("/api/v1/trips/{tripId}")
    TripDetailDto getTripDetail(@PathVariable Long tripId);
}
