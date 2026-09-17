package vn.railticketing.gateway.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import vn.railticketing.gateway.client.dto.VerifyResponse;

@HttpExchange
public interface WaitingRoomClient {

    @GetExchange("/tickets/{ticketId}/verify")
    VerifyResponse verify(@PathVariable String ticketId);
}
