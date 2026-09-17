package vn.railticketing.inventory.web.dto;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import vn.railticketing.inventory.domain.BerthInventory;

import java.util.List;

@Mapper(componentModel = "spring")
public interface BerthMapper {

    @Mapping(target = "carriageNo", source = "carriageNo", qualifiedByName = "shortToInt")
    @Mapping(target = "berthNo", source = "berthNo", qualifiedByName = "shortToInt")
    @Mapping(target = "level", source = "level", qualifiedByName = "shortToInteger")
    @Mapping(target = "priceVnd", source = "priceVnd", qualifiedByName = "longToLong")
    BerthDto toDto(BerthInventory berth);

    List<BerthDto> toDtoList(List<BerthInventory> berths);

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
