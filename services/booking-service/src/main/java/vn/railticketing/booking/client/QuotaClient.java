package vn.railticketing.booking.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import vn.railticketing.booking.client.dto.QuotaCheckRequest;
import vn.railticketing.booking.client.dto.QuotaCheckResponse;

import java.util.UUID;

@HttpExchange
public interface QuotaClient {

    @PostExchange("/api/v1/quota/reserve")
    QuotaCheckResponse checkAndReserve(@RequestBody QuotaCheckRequest request);

    @DeleteExchange("/api/v1/quota/reservations/{bookingId}")
    void releaseQuota(@PathVariable UUID bookingId);
}
