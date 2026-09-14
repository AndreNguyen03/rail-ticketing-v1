package vn.railticketing.inventory.client.dto;

import java.util.List;

public record ScheduleTripDetail(
        Long tripId,
        List<ScheduleCarriage> carriages
) {}
