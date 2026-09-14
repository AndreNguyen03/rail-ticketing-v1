package vn.railticketing.inventory.client.dto;

import java.util.List;

public record ScheduleCarriage(
        int carriageNo,
        String berthClass,
        List<ScheduleBerth> berths
) {}
