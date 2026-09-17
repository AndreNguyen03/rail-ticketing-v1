package vn.railticketing.inventory.client.dto;

// Schedule berth subset: carriages[].berths[].
public record ScheduleBerth(
        Long berthId,
        int carriageNo,
        int berthNo,
        String berthClass,
        Integer level,
        long priceVnd
) {}
