package vn.railticketing.booking.client.dto;

import java.util.List;

public record QuotaCheckResponse(
        boolean             allowed,
        Long                saleWindowId,
        List<QuotaViolation> violations
) {}
