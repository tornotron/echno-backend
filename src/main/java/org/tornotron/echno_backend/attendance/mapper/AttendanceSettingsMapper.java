package org.tornotron.echno_backend.attendance.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.dto.AttendanceSettingsDto;

/**
 * Maps {@link AttendanceSettings} to its DTO. The owning organization flattens to its id and the
 * default shift maps through {@link ShiftTimingMapper}; everything else copies by name.
 */
@Mapper(componentModel = "spring", uses = ShiftTimingMapper.class)
public interface AttendanceSettingsMapper {

    @Mapping(source = "organization.id", target = "organizationId")
    AttendanceSettingsDto toDto(AttendanceSettings entity);
}
