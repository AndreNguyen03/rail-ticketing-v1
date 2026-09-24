package vn.railticketing.quota.web.dto;

import java.util.List;

public record QuotaReserveResponse(
        boolean             allowed,
        Long                saleWindowId,
        List<QuotaViolationDto> violations
) {
    public static QuotaReserveResponse allowed(Long saleWindowId) {
        return new QuotaReserveResponse(true, saleWindowId, List.of());
    }

    public static QuotaReserveResponse denied(Long saleWindowId, List<QuotaViolationDto> violations) {
        return new QuotaReserveResponse(false, saleWindowId, violations);
    }
}
