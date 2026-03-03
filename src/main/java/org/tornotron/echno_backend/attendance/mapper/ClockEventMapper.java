package org.tornotron.echno_backend.attendance.mapper;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.dto.ClockEventDto;

@Component
public class ClockEventMapper {

    public ClockEventDto toDto(ClockEvent entity) {
        if (entity == null) return null;
        return ClockEventDto.builder()
                .id(entity.getId())
                .eventType(entity.getEventType())
                .eventTimestamp(entity.getEventTimestamp())
                .latitude(entity.getLatitude())
                .longitude(entity.getLongitude())
                .gpsAccuracy(entity.getGpsAccuracy())
                .photoUrl(entity.getPhotoUrl())
                .projectId(entity.getProjectId())
                .projectName(entity.getProjectName())
                .devicePlatform(entity.getDevicePlatform())
                .isWithinGeofence(entity.getIsWithinGeofence())
                .distanceFromProject(entity.getDistanceFromProject())
                .remarks(entity.getRemarks())
                .verifiedBy(entity.getVerifiedBy())
                .verifiedAt(entity.getVerifiedAt())
                .isRegularized(entity.getIsRegularized())
                .regularizationReason(entity.getRegularizationReason())
                .build();
    }
}
