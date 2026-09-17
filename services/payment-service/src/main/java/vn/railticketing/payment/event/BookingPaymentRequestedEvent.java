package vn.railticketing.payment.event;

// Payload of booking.payment-requested.v1 (consumed).
public record BookingPaymentRequestedEvent(String bookingId) {}
