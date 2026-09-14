package vn.railticketing.schedule.web.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record TripSummaryDto(
        Long tripId,
        String trainCode,
        LocalDate departureDate,
        int fromStationIndex,
        int toStationIndex,
        OffsetDateTime departsAt,
        OffsetDateTime arrivesAt,
        int durationMinutes,
        long minPriceVnd
) {}
