package org.tornotron.echno_backend.attendance.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.attendance.MovementRecord;
import org.tornotron.echno_backend.attendance.dto.MovementRecordDto;

import java.util.Collections;
import java.util.List;

@Component
public class MovementRecordMapper {

    private final ObjectMapper objectMapper;

    public MovementRecordMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MovementRecordDto toDto(MovementRecord entity) {
        if (entity == null) return null;
        return MovementRecordDto.builder()
                .id(entity.getId())
                .attendanceId(entity.getAttendance() != null ? entity.getAttendance().getId() : null)
                .employeeId(entity.getEmployeeId())
                .employeeName(entity.getEmployeeName())
                .movementType(entity.getMovementType())
                .fromLocation(entity.getFromLocation())
                .toLocation(entity.getToLocation())
                .startTime(entity.getStartTime())
                .endTime(entity.getEndTime())
                .durationMinutes(entity.getDurationMinutes())
                .distanceKm(entity.getDistanceKm())
                .purpose(entity.getPurpose())
                .remarks(entity.getRemarks())
                .startLatitude(entity.getStartLatitude())
                .startLongitude(entity.getStartLongitude())
                .endLatitude(entity.getEndLatitude())
                .endLongitude(entity.getEndLongitude())
                .attachments(deserializeAttachments(entity.getAttachments()))
                .verifiedBy(entity.getVerifiedBy())
                .verifiedAt(entity.getVerifiedAt())
                .isVerified(entity.getIsVerified())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public String serializeAttachments(List<String> attachments) {
        if (attachments == null || attachments.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(attachments);
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> deserializeAttachments(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
