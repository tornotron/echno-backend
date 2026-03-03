package org.tornotron.echno_backend.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceSettingsPatchDto {
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
    private Long defaultShiftTimingId;
}
