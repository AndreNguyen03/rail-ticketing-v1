package vn.railticketing.booking.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import vn.railticketing.booking.client.dto.CreateHoldRequest;
import vn.railticketing.booking.client.dto.HoldResponse;

import java.util.UUID;

@HttpExchange
public interface InventoryClient {

    @GetExchange("/api/v1/holds/{holdId}")
    HoldResponse getHold(@PathVariable UUID holdId);

    @PostExchange("/api/v1/holds")
    HoldResponse createHold(@RequestBody CreateHoldRequest request,
                             @RequestHeader("Idempotency-Key") UUID idempotencyKey);

    @DeleteExchange("/api/v1/holds/{holdId}")
    void releaseHold(@PathVariable UUID holdId);

    @PostExchange("/api/v1/holds/{holdId}/commit")
    void commitHold(@PathVariable UUID holdId);
}
