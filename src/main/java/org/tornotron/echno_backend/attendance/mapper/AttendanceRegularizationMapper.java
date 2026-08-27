package org.tornotron.echno_backend.attendance.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.attendance.AttendanceRegularization;
import org.tornotron.echno_backend.attendance.dto.AttendanceRegularizationDto;
import org.tornotron.echno_backend.attendance.dto.ClockEventCreationDto;

import java.util.Collections;
import java.util.List;

@Component
public class AttendanceRegularizationMapper {

    private final ObjectMapper objectMapper;

    public AttendanceRegularizationMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AttendanceRegularizationDto toDto(AttendanceRegularization entity) {
        if (entity == null) return null;
        return AttendanceRegularizationDto.builder()
                .id(entity.getId())
                .attendanceId(entity.getAttendance() != null ? entity.getAttendance().getId() : null)
                .reason(entity.getReason())
                .requestedBy(entity.getRequestedBy())
                .requestedById(entity.getRequestedById())
                .requestedAt(entity.getRequestedAt())
                .approvedBy(entity.getApprovedBy())
                .approvedById(entity.getApprovedById())
                .approvedAt(entity.getApprovedAt())
                .status(entity.getStatus())
                .rejectionReason(entity.getRejectionReason())
                .missingEvents(deserializeMissingEvents(entity.getMissingEvents()))
                .build();
    }

    public String serializeMissingEvents(List<String> events) {
        if (events == null || events.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> deserializeMissingEvents(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Serializes the corrected clock events submitted with a request so they survive until approval.
     *
     * <p>Returns {@code null} for an absent or empty list, matching the nullable column, and also on
     * a serialization failure: a request that cannot carry its corrections is still a valid request
     * for the reason the employee gave, and the manager can enter the times by hand.
     */
    public String serializeRequestedEvents(List<ClockEventCreationDto> events) {
        if (events == null || events.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads back the corrected clock events stored by {@link #serializeRequestedEvents}. */
    public List<ClockEventCreationDto> deserializeRequestedEvents(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<ClockEventCreationDto>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
