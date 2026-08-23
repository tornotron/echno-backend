package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Payload to create an attendance settings profile for an organization or a project.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceSettingsCreationDto {

    @Schema(description = "Display name for this settings profile.", example = "Standard Site Attendance")
    @NotBlank
    private String settingName;

    @Schema(description = "Id of the project this profile applies to. Omit to create an organization-level default.", example = "12")
    private Long projectId;

    @Schema(description = "Number of check-in/out cycles expected per day.", example = "2")
    @NotNull
    @Min(1) @Max(4)
    private Integer checkInOutCycles;

    @Schema(description = "Whether a photo is required on check-in.", example = "true")
    @NotNull
    private Boolean photoRequiredOnCheckIn;

    @Schema(description = "Whether a photo is required on check-out.", example = "true")
    @NotNull
    private Boolean photoRequiredOnCheckOut;

    @Schema(description = "Whether GPS coordinates are required on clock events.", example = "true")
    @NotNull
    private Boolean geolocationRequired;

    @Schema(description = "Radius in metres around the project within which a clock event is considered on site.", example = "200")
    @NotNull
    @Min(50) @Max(5000)
    private Integer geofenceRadiusMeters;

    @Schema(description = "Whether movements away from the checked-in location must be logged.", example = "true")
    @NotNull
    private Boolean movementTrackingEnabled;

    @Schema(description = "Whether a photo is required when logging a movement.", example = "false")
    @NotNull
    private Boolean movementPhotoRequired;

    @Schema(description = "Whether GPS coordinates are required when logging a movement.", example = "true")
    @NotNull
    private Boolean movementGeolocationRequired;

    @Schema(description = "Hours after shift start with no clock event before the employee is auto-marked absent.", example = "4")
    @NotNull
    @Min(1) @Max(24)
    private Integer autoMarkAbsentAfterHours;

    @Schema(description = "Whether an employee can submit their own regularization requests.", example = "true")
    @NotNull
    private Boolean allowSelfRegularization;

    @Schema(description = "Whether a manager must approve a regularization request before it takes effect.", example = "true")
    @NotNull
    private Boolean regularizationApprovalRequired;

    @Schema(description = "Maximum number of regularization days allowed per employee per month.", example = "3")
    @NotNull
    @Min(0) @Max(31)
    private Integer maxRegularizationDaysPerMonth;

    @Schema(description = "Id of the shift timing to use when a check-in does not specify one.", example = "5")
    private Long defaultShiftTimingId;
}
