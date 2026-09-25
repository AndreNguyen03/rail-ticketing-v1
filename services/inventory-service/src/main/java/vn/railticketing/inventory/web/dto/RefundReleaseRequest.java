package vn.railticketing.inventory.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record RefundReleaseRequest(
        @NotEmpty List<Long> berthIds,
        int journeyMask
) {}
