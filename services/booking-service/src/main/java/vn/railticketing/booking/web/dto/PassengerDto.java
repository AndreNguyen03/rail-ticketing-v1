package vn.railticketing.booking.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PassengerDto(
        @NotBlank String fullName,
        @NotBlank @Pattern(regexp = "^[0-9]{12}$") String idNumber,
        @NotBlank String passengerType
) {}
