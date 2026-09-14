package vn.railticketing.schedule.web.dto;

import java.util.List;

public record CarriageDto(
        int carriageNo,
        String berthClass,
        List<BerthDto> berths
) {}
