package vn.railticketing.booking.event;

import java.util.UUID;

public record BookingConfirmedEvent(UUID bookingId, String status) {}
