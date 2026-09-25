package vn.railticketing.booking.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ExchangeRequest(
        @NotBlank String passengerIdNumber,
        Long  newTripId,           // null = same trip
        int   newFromStationIndex,
        int   newToStationIndex
) {}
