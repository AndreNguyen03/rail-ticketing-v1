package vn.railticketing.fare.web.dto;

public record FareByClassDto(
        String berthClass,
        long   minPriceVnd,
        long   maxPriceVnd,
        int    availableCount
) {}
