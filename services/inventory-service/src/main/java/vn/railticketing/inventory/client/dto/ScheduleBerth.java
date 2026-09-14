package vn.railticketing.inventory.client.dto;

// Mirrors fields from schedule-service GET /api/v1/trips/{tripId} → carriages[].berths[]
public record ScheduleBerth(
        Long berthId,
        int carriageNo,
        int berthNo,
        String berthClass,
        Integer level,
        long priceVnd
) {}
