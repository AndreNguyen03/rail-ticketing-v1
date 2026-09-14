package vn.railticketing.booking.web.dto;

import java.util.UUID;

public record TicketDto(
        UUID ticketId,
        Long berthId,
        int carriageNo,
        int berthNo,
        String passengerName,
        long priceVnd
) {}
