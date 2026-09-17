package vn.railticketing.booking.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmBookingRequest(
        @NotBlank String paymentMethod   // MOCK|VNPAY|MOMO, stage 0 is MOCK only.
) {}
