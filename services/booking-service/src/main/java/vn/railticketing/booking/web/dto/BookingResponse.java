package vn.railticketing.booking.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingResponse(
        UUID bookingId,
        String status,
        Long tripId,
        UUID holdId,
        Long totalPriceVnd,
        ContactDto contact,
        List<PassengerDto> passengers,
        List<TicketDto> tickets,
        Instant expiresAt,
        Instant createdAt
) {}
