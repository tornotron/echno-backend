package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

@Schema(description = "Partial update to a shift timing. Only the fields provided are changed.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShiftTimingPatchDto {

    @Schema(description = "Display name of the shift.", example = "General Shift")
    private String shiftName;

    @Schema(description = "Scheduled start time.", example = "09:00:00")
    private LocalTime startTime;

    @Schema(description = "Scheduled end time.", example = "18:00:00")
    private LocalTime endTime;

    @Schema(description = "Start of the lunch break.", example = "13:00:00")
    private LocalTime lunchBreakStart;

    @Schema(description = "End of the lunch break.", example = "13:30:00")
    private LocalTime lunchBreakEnd;

    @Schema(description = "Minutes of grace allowed after startTime before an arrival counts as late.", example = "15")
    private Integer gracePeriodMinutes;

    @Schema(description = "Minimum hours worked to be counted as a full day.", example = "8.0")
    private BigDecimal minimumWorkHours;

    @Schema(description = "Hours worked below which the day is counted as a half day.", example = "4.0")
    private BigDecimal halfDayWorkHours;

    @Schema(description = "Hours worked beyond which time is counted as overtime.", example = "9.0")
    private BigDecimal overtimeThreshold;
}
