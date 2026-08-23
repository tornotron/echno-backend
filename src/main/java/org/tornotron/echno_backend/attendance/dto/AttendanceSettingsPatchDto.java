package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Partial update to an attendance settings profile. Only the fields provided are changed.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceSettingsPatchDto {

    @Schema(description = "Display name for this settings profile.", example = "Standard Site Attendance")
    private String settingName;

    @Schema(description = "Number of check-in/out cycles expected per day.", example = "2")
    private Integer checkInOutCycles;

    @Schema(description = "Whether a photo is required on check-in.", example = "true")
    private Boolean photoRequiredOnCheckIn;

    @Schema(description = "Whether a photo is required on check-out.", example = "true")
    private Boolean photoRequiredOnCheckOut;

    @Schema(description = "Whether GPS coordinates are required on clock events.", example = "true")
    private Boolean geolocationRequired;

    @Schema(description = "Radius in metres around the project within which a clock event is considered on site.", example = "250")
    private Integer geofenceRadiusMeters;

    @Schema(description = "Whether movements away from the checked-in location must be logged.", example = "true")
    private Boolean movementTrackingEnabled;

    @Schema(description = "Whether a photo is required when logging a movement.", example = "false")
    private Boolean movementPhotoRequired;

    @Schema(description = "Whether GPS coordinates are required when logging a movement.", example = "true")
    private Boolean movementGeolocationRequired;

    @Schema(description = "Hours after shift start with no clock event before the employee is auto-marked absent.", example = "4")
    private Integer autoMarkAbsentAfterHours;

    @Schema(description = "Whether an employee can submit their own regularization requests.", example = "true")
    private Boolean allowSelfRegularization;

    @Schema(description = "Whether a manager must approve a regularization request before it takes effect.", example = "true")
    private Boolean regularizationApprovalRequired;

    @Schema(description = "Maximum number of regularization days allowed per employee per month.", example = "3")
    private Integer maxRegularizationDaysPerMonth;

    @Schema(description = "Id of the shift timing to use when a check-in does not specify one.", example = "5")
    private Long defaultShiftTimingId;
}
