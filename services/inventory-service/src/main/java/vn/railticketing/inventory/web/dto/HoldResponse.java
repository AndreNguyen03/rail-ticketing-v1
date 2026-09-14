package vn.railticketing.inventory.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldResponse(
        UUID holdId,
        Long tripId,
        int fromStationIndex,
        int toStationIndex,
        List<BerthDto> berths,
        long totalPriceVnd,
        Instant expiresAt,
        long ttlSeconds
) {}
