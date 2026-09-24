package vn.railticketing.quota.web.dto;

import java.time.Instant;

public record ActiveWindowResponse(
        Long    saleWindowId,
        String  name,
        Instant startsAt,
        Instant endsAt,
        int     quotaLimit
) {}
