package vn.railticketing.booking.client.dto;

public record HoldBerthDto(
        Long berthId,
        int carriageNo,
        int berthNo,
        String berthClass,
        Integer level,
        long priceVnd
) {}
