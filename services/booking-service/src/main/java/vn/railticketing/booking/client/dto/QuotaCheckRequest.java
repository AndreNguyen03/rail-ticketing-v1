package vn.railticketing.booking.client.dto;

import java.util.List;
import java.util.UUID;

public record QuotaCheckRequest(
        UUID                      bookingId,
        short                     fromStationIndex,
        short                     toStationIndex,
        List<QuotaPassengerEntry> passengers
) {}
