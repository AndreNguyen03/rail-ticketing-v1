package vn.railticketing.booking.event;

import java.util.UUID;

public record BookingRefundedEvent(
        UUID bookingId,
        long refundAmountVnd
) {}
