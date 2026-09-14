package vn.railticketing.booking.client.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldResponse(
        UUID holdId,
        Long tripId,
        int fromStationIndex,
        int toStationIndex,
        List<HoldBerthDto> berths,
        long totalPriceVnd,
        Instant expiresAt,
        long ttlSeconds
) {}
