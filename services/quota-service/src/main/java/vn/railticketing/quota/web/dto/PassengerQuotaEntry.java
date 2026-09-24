package vn.railticketing.quota.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record PassengerQuotaEntry(
        @NotBlank String passengerIdNumber,
        @Positive int    ticketCount
) {}
