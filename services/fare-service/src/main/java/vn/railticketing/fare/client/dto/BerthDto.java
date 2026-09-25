package vn.railticketing.fare.client.dto;

public record BerthDto(
        Long    berthId,
        int     carriageNo,
        int     berthNo,
        String  berthClass,
        Integer level,
        long    priceVnd
) {}
