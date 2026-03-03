package org.tornotron.echno_backend.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceSettingsDto {
    private Long id;
    private Long organizationId;
    private Long projectId;
    private String settingName;
    private Integer checkInOutCycles;
    private Boolean photoRequiredOnCheckIn;
    private Boolean photoRequiredOnCheckOut;
    private Boolean geolocationRequired;
    private Integer geofenceRadiusMeters;
    private Boolean movementTrackingEnabled;
    private Boolean movementPhotoRequired;
    private Boolean movementGeolocationRequired;
    private Integer autoMarkAbsentAfterHours;
    private Boolean allowSelfRegularization;
    private Boolean regularizationApprovalRequired;
    private Integer maxRegularizationDaysPerMonth;
    private ShiftTimingDto defaultShiftTiming;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
