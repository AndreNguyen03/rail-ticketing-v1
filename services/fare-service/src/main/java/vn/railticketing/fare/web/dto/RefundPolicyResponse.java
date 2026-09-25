package vn.railticketing.fare.web.dto;

import java.util.List;

public record RefundPolicyResponse(
        List<RefundTierDto> tiers,
        String              note
) {
    public record RefundTierDto(
            int    minHoursBeforeDeparture,
            int    maxHoursBeforeDeparture,  // -1 = no upper bound
            int    refundPercentage
    ) {}

    // Static policy: no time-of-day adjustments, purely hours-based.
    public static RefundPolicyResponse standard() {
        return new RefundPolicyResponse(
                List.of(
                        new RefundTierDto(24, -1, 100),
                        new RefundTierDto(12, 24, 50),
                        new RefundTierDto(0,  12, 0)
                ),
                "Refund percentage is based on hours remaining before departure at origin station."
        );
    }
}
