package vn.railticketing.booking.event;

import java.util.UUID;

public record BookingPaymentFailedEvent(UUID bookingId, String status) {}
