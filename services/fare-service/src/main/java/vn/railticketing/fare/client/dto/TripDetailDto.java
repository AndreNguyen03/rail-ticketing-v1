package vn.railticketing.fare.client.dto;

import java.time.LocalDate;
import java.util.List;

public record TripDetailDto(
        Long           tripId,
        String         trainCode,
        LocalDate      departureDate,
        List<StationDto>  stations,
        List<CarriageDto> carriages
) {}
