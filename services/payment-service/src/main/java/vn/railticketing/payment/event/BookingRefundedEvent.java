package vn.railticketing.payment.event;

// Payload of booking.refunded.v1 (consumed by payment-service to reverse charge).
public record BookingRefundedEvent(
        String bookingId,
        long   refundAmountVnd
) {}
