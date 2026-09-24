package vn.railticketing.quota.web.dto;

public record QuotaViolationDto(
        String passengerIdNumber,
        int    current,
        int    limit
) {}
