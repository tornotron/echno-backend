package org.tornotron.echno_backend.attendance.mapper;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.dto.AttendanceSettingsDto;

@Component
public class AttendanceSettingsMapper {

    private final ShiftTimingMapper shiftTimingMapper;

    public AttendanceSettingsMapper(ShiftTimingMapper shiftTimingMapper) {
        this.shiftTimingMapper = shiftTimingMapper;
    }

    public AttendanceSettingsDto toDto(AttendanceSettings entity) {
        if (entity == null) return null;
        return AttendanceSettingsDto.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganization() != null ? entity.getOrganization().getId() : null)
                .projectId(entity.getProjectId())
                .settingName(entity.getSettingName())
                .checkInOutCycles(entity.getCheckInOutCycles())
                .photoRequiredOnCheckIn(entity.getPhotoRequiredOnCheckIn())
                .photoRequiredOnCheckOut(entity.getPhotoRequiredOnCheckOut())
                .geolocationRequired(entity.getGeolocationRequired())
                .geofenceRadiusMeters(entity.getGeofenceRadiusMeters())
                .movementTrackingEnabled(entity.getMovementTrackingEnabled())
                .movementPhotoRequired(entity.getMovementPhotoRequired())
                .movementGeolocationRequired(entity.getMovementGeolocationRequired())
                .autoMarkAbsentAfterHours(entity.getAutoMarkAbsentAfterHours())
                .allowSelfRegularization(entity.getAllowSelfRegularization())
                .regularizationApprovalRequired(entity.getRegularizationApprovalRequired())
                .maxRegularizationDaysPerMonth(entity.getMaxRegularizationDaysPerMonth())
                .defaultShiftTiming(shiftTimingMapper.toDto(entity.getDefaultShiftTiming()))
                .isActive(entity.getIsActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
