package vn.railticketing.booking.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmBookingRequest(
        @NotBlank String paymentMethod   // MOCK | VNPAY | MOMO — only MOCK at stage 0
) {}
