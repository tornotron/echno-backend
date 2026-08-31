package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Schema(description = "Attendance settings profile in force for an organization or a single project.")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceSettingsDto {

    @Schema(description = "Id of this settings profile.", example = "3")
    private Long id;

    @Schema(description = "Id of the owning organization.", example = "1")
    private Long organizationId;

    @Schema(description = "Id of the project this profile applies to, or null for the organization-level default.", example = "12", nullable = true)
    private Long projectId;

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

    @Schema(description = "Radius in metres around the project within which a clock event is considered on site.", example = "200")
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

    @Schema(description = "Shift timing used when a check-in does not specify one.")
    private ShiftTimingDto defaultShiftTiming;

    @Schema(description = "Whether this settings profile is currently in effect.", example = "true")
    private Boolean isActive;

    @Schema(description = "Timestamp the settings profile was created.", example = "2026-01-15T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Timestamp the settings profile was last updated.", example = "2026-02-10T11:30:00")
    private LocalDateTime updatedAt;
}
