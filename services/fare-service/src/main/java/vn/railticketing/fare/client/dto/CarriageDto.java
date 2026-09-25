package vn.railticketing.fare.client.dto;

import java.util.List;

public record CarriageDto(
        int           carriageNo,
        String        berthClass,
        List<BerthDto> berths
) {}
