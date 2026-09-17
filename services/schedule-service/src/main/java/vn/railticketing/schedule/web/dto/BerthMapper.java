package vn.railticketing.schedule.web.dto;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import vn.railticketing.schedule.domain.Berth;

import java.util.List;

@Mapper(componentModel = "spring")
public interface BerthMapper {

    @Mapping(target = "carriageNo", source = "carriageNo", qualifiedByName = "shortToInt")
    @Mapping(target = "berthNo", source = "berthNo", qualifiedByName = "shortToInt")
    @Mapping(target = "level", source = "level", qualifiedByName = "shortToInteger")
    @Mapping(target = "priceVnd", source = "priceVnd", qualifiedByName = "longToLong")
    BerthDto toDto(Berth berth);

    List<BerthDto> toDtoList(List<Berth> berths);

    @Named("shortToInt")
    default int shortToInt(Short value) {
        return value != null ? value.intValue() : 0;
    }

    @Named("shortToInteger")
    default Integer shortToInteger(Short value) {
        return value != null ? value.intValue() : null;
    }

    @Named("longToLong")
    default long longToLong(Long value) {
        return value != null ? value : 0L;
    }
}
