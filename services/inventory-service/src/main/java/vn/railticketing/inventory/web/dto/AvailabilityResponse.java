package vn.railticketing.inventory.web.dto;

import java.util.List;
import java.util.Map;

public record AvailabilityResponse(
        Long tripId,
        int fromStationIndex,
        int toStationIndex,
        int availableCount,
        Map<String, Integer> countByClass,
        List<BerthDto> berths
) {}
