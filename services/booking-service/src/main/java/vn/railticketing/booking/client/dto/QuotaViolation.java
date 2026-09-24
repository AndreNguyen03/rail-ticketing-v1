package vn.railticketing.booking.client.dto;

public record QuotaViolation(
        String passengerIdNumber,
        int    current,
        int    limit
) {}
