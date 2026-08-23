package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

@Schema(description = "Payload to create a named work shift.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShiftTimingCreationDto {

    @Schema(description = "Display name of the shift.", example = "General Shift")
    @NotBlank
    private String shiftName;

    @Schema(description = "Scheduled start time.", example = "09:00:00")
    @NotNull
    private LocalTime startTime;

    @Schema(description = "Scheduled end time.", example = "18:00:00")
    @NotNull
    private LocalTime endTime;

    @Schema(description = "Start of the lunch break.", example = "13:00:00")
    @NotNull
    private LocalTime lunchBreakStart;

    @Schema(description = "End of the lunch break.", example = "13:30:00")
    @NotNull
    private LocalTime lunchBreakEnd;

    @Schema(description = "Minutes of grace allowed after startTime before an arrival counts as late.", example = "15")
    @NotNull
    @Min(0) @Max(120)
    private Integer gracePeriodMinutes;

    @Schema(description = "Minimum hours worked to be counted as a full day.", example = "8.0")
    @NotNull
    private BigDecimal minimumWorkHours;

    @Schema(description = "Hours worked below which the day is counted as a half day.", example = "4.0")
    @NotNull
    private BigDecimal halfDayWorkHours;

    @Schema(description = "Hours worked beyond which time is counted as overtime.", example = "9.0")
    @NotNull
    private BigDecimal overtimeThreshold;
}
