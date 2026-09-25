package vn.railticketing.fare.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record FareResponse(
        Long                tripId,
        int                 fromStationIndex,
        int                 toStationIndex,
        OffsetDateTime      departureAt,         // departure of fromStation — used for refund policy
        List<FareByClassDto> faresByClass
) {}
