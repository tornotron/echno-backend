package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Schema(description = "A named work shift with its timing windows and work-hour thresholds.")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShiftTimingDto {

    @Schema(description = "Id of the shift timing.", example = "5")
    private Long id;

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

    @Schema(description = "Timestamp the shift timing was created.", example = "2026-01-01T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Timestamp the shift timing was last updated.", example = "2026-02-05T10:00:00")
    private LocalDateTime updatedAt;
}
