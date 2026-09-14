package vn.railticketing.booking.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ContactDto(
        @NotBlank String fullName,
        @NotBlank @Pattern(regexp = "^0[0-9]{9}$") String phone,
        String email
) {}
