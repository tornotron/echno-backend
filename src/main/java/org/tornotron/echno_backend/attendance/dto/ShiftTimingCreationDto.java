package org.tornotron.echno_backend.attendance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShiftTimingCreationDto {

    @NotBlank
    private String shiftName;

    @NotNull
    private LocalTime startTime;

    @NotNull
    private LocalTime endTime;

    @NotNull
    private LocalTime lunchBreakStart;

    @NotNull
    private LocalTime lunchBreakEnd;

    @NotNull
    @Min(0) @Max(120)
    private Integer gracePeriodMinutes;

    @NotNull
    private BigDecimal minimumWorkHours;

    @NotNull
    private BigDecimal halfDayWorkHours;

    @NotNull
    private BigDecimal overtimeThreshold;
}
