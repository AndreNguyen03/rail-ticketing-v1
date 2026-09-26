package vn.railticketing.ticket.event;

import java.util.List;
import java.util.UUID;

public record BookingConfirmedPayload(
        UUID   bookingId,
        String status,
        Long   tripId,
        String contactEmail,
        String contactName,
        List<TicketInfo> tickets
) {
    public record TicketInfo(
            UUID   ticketId,
            Long   berthId,
            short  carriageNo,
            short  berthNo,
            String passengerName,
            String passengerIdNumber,
            String passengerType,
            long   priceVnd
    ) {}
}
