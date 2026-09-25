package vn.railticketing.booking.web.dto;

import java.util.UUID;

public record ExchangeResponse(
        UUID  bookingId,
        UUID  ticketId,
        Long  newBerthId,
        Short newCarriageNo,
        Short newBerthNo,
        long  fareDifferenceVnd   // positive = extra charge; negative = partial refund
) {}
