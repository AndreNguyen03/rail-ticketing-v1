package vn.railticketing.booking.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import vn.railticketing.booking.client.dto.RefundReleaseRequest;

import java.util.UUID;

@HttpExchange
public interface InventoryRefundClient {

    @PostExchange("/api/v1/inventory/release-occupied")
    void releaseOccupied(@RequestBody RefundReleaseRequest request);

    @PostExchange("/api/v1/inventory/exchange")
    void exchange(@RequestParam("oldBerthId")  Long oldBerthId,
                  @RequestParam("journeyMask") int  journeyMask,
                  @RequestParam("newHoldId")   UUID newHoldId);
}
