package vn.railticketing.booking.web.dto;

import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        UUID    bookingId,
        String  status,
        long    refundAmountVnd,
        Instant refundedAt
) {}
