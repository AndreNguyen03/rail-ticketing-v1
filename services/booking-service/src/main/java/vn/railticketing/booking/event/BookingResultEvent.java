package vn.railticketing.booking.event;

// Payload of payment.completed.v1 (payment-service's outcome for a booking).
public record BookingResultEvent(String bookingId, String result) {}
