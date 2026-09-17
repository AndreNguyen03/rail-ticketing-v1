package vn.railticketing.payment.event;

// Payload of payment.completed.v1 (produced).
public record BookingResultEvent(String bookingId, String result) {}
