package vn.railticketing.booking.event;

import java.util.UUID;

public record BookingPaymentRequestedEvent(UUID bookingId) {}
