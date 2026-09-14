package vn.railticketing.inventory.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateHoldRequest(
        @NotNull Long tripId,
        @Min(0) int fromStationIndex,
        @Min(1) int toStationIndex,
        @Min(1) @Max(4) int quantity,
        String preferredClass   // nullable — null means any class
) {}
