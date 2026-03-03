package org.tornotron.echno_backend.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShiftTimingDto {
    private Long id;
    private String shiftName;
    private LocalTime startTime;
    private LocalTime endTime;
    private LocalTime lunchBreakStart;
    private LocalTime lunchBreakEnd;
    private Integer gracePeriodMinutes;
    private BigDecimal minimumWorkHours;
    private BigDecimal halfDayWorkHours;
    private BigDecimal overtimeThreshold;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
