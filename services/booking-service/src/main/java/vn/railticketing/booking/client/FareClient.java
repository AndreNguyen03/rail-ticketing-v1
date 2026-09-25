package vn.railticketing.booking.client;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.time.OffsetDateTime;

@HttpExchange
public interface FareClient {

    @GetExchange("/api/v1/fares/refund-amount")
    Long getRefundAmount(@RequestParam long          ticketPriceVnd,
                         @RequestParam OffsetDateTime departureAt);
}
