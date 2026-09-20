package org.tornotron.echno_backend.holiday.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.holiday.Holiday;
import org.tornotron.echno_backend.holiday.dto.HolidayDto;

@Mapper(componentModel = "spring")
public interface HolidayMapper {

    @Mapping(source = "organization.id", target = "organizationId")
    HolidayDto toDto(Holiday holiday);
}
