package vn.railticketing.fare.client.dto;

import java.time.OffsetDateTime;

public record StationDto(
        int           stationIndex,
        String        stationCode,
        String        stationName,
        OffsetDateTime arrivesAt,
        OffsetDateTime departsAt
) {}
