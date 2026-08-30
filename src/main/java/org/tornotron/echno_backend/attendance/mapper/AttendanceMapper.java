package org.tornotron.echno_backend.attendance.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;

/**
 * Maps {@link Attendance} to the response DTO. The shift maps through {@link ShiftTimingMapper},
 * the clock events through {@link ClockEventMapper}, the regularizations through
 * {@link AttendanceRegularizationMapper} and the movements through {@link MovementRecordMapper};
 * everything else copies by name.
 *
 * <p>The clock events used to need a {@code FileStorageService} threaded in from the service to
 * sign their attachment URLs. {@link ClockEventMapper} now delegates that to the shared attachment
 * mapper, which holds the service itself, so the parameter is gone from this signature too.
 */
@Mapper(componentModel = "spring",
        uses = {ShiftTimingMapper.class, ClockEventMapper.class, AttendanceRegularizationMapper.class,
                MovementRecordMapper.class})
public interface AttendanceMapper {

    AttendanceResponseDto toResponseDto(Attendance attendance);
}
