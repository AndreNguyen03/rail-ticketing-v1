package vn.railticketing.booking.client.dto;

public record CreateHoldRequest(
        Long   tripId,
        int    fromStationIndex,
        int    toStationIndex,
        int    quantity,
        String preferredClass    // null = any class
) {}
