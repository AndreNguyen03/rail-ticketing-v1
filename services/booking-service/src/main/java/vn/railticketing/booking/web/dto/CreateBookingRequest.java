package vn.railticketing.booking.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateBookingRequest(
        @NotNull UUID holdId,
        @NotNull @Valid ContactDto contact,
        @NotNull @Size(min = 1, max = 4) List<@Valid PassengerDto> passengers
) {}
