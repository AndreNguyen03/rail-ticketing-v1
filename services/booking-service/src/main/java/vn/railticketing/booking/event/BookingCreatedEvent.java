package vn.railticketing.booking.event;

import java.util.UUID;

public record BookingCreatedEvent(UUID bookingId, Long tripId, String status) {}
