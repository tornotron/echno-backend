package org.tornotron.echno_backend.attendance.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.dto.ShiftTimingDto;

/**
 * Maps {@link ShiftTiming} to its DTO. Every field copies by name; the owning organization is
 * not part of the response.
 */
@Mapper(componentModel = "spring")
public interface ShiftTimingMapper {

    ShiftTimingDto toDto(ShiftTiming entity);
}
