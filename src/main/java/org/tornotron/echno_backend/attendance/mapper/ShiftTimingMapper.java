package org.tornotron.echno_backend.attendance.mapper;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.dto.ShiftTimingDto;

@Component
public class ShiftTimingMapper {

    public ShiftTimingDto toDto(ShiftTiming entity) {
        if (entity == null) return null;
        return ShiftTimingDto.builder()
                .id(entity.getId())
                .shiftName(entity.getShiftName())
                .startTime(entity.getStartTime())
                .endTime(entity.getEndTime())
                .lunchBreakStart(entity.getLunchBreakStart())
                .lunchBreakEnd(entity.getLunchBreakEnd())
                .gracePeriodMinutes(entity.getGracePeriodMinutes())
                .minimumWorkHours(entity.getMinimumWorkHours())
                .halfDayWorkHours(entity.getHalfDayWorkHours())
                .overtimeThreshold(entity.getOvertimeThreshold())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
