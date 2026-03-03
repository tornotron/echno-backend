package org.tornotron.echno_backend.attendance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceSettingsCreationDto {

    @NotBlank
    private String settingName;

    private Long projectId;

    @NotNull
    @Min(1) @Max(4)
    private Integer checkInOutCycles;

    @NotNull
    private Boolean photoRequiredOnCheckIn;

    @NotNull
    private Boolean photoRequiredOnCheckOut;

    @NotNull
    private Boolean geolocationRequired;

    @NotNull
    @Min(50) @Max(5000)
    private Integer geofenceRadiusMeters;

    @NotNull
    private Boolean movementTrackingEnabled;

    @NotNull
    private Boolean movementPhotoRequired;

    @NotNull
    private Boolean movementGeolocationRequired;

    @NotNull
    @Min(1) @Max(24)
    private Integer autoMarkAbsentAfterHours;

    @NotNull
    private Boolean allowSelfRegularization;

    @NotNull
    private Boolean regularizationApprovalRequired;

    @NotNull
    @Min(0) @Max(31)
    private Integer maxRegularizationDaysPerMonth;

    private Long defaultShiftTimingId;
}
