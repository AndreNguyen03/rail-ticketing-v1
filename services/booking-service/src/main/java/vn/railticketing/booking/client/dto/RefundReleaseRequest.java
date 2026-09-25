package vn.railticketing.booking.client.dto;

import java.util.List;

public record RefundReleaseRequest(
        List<Long> berthIds,
        int        journeyMask
) {}
