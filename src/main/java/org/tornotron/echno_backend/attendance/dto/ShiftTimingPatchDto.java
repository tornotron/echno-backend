package org.tornotron.echno_backend.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShiftTimingPatchDto {
    private String shiftName;
    private LocalTime startTime;
    private LocalTime endTime;
    private LocalTime lunchBreakStart;
    private LocalTime lunchBreakEnd;
    private Integer gracePeriodMinutes;
    private BigDecimal minimumWorkHours;
    private BigDecimal halfDayWorkHours;
    private BigDecimal overtimeThreshold;
}
