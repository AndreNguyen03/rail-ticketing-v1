package vn.railticketing.quota.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record QuotaReserveRequest(
        @NotNull UUID bookingId,
        short fromStationIndex,
        short toStationIndex,
        @NotEmpty List<@Valid PassengerQuotaEntry> passengers
) {}
