package org.tornotron.echno_backend.attendance.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.tornotron.echno_backend.attendance.AttendanceRegularization;
import org.tornotron.echno_backend.attendance.dto.AttendanceRegularizationDto;
import org.tornotron.echno_backend.attendance.dto.ClockEventCreationDto;

import java.util.Collections;
import java.util.List;

/**
 * Maps {@link AttendanceRegularization} to its DTO. The parent attendance flattens to its id and
 * the missing events are held on the row as a JSON string, so they are read back here; everything
 * else copies by name.
 *
 * <p>Abstract class rather than interface because the two JSON pairs are real code: the request
 * carries a list of missing events and a list of corrected clock events, both stored as JSON on
 * the row and both read back on the way out.
 */
@Mapper(componentModel = "spring")
public abstract class AttendanceRegularizationMapper {

    @Autowired
    protected ObjectMapper objectMapper;

    @Mapping(source = "attendance.id", target = "attendanceId")
    @Mapping(target = "missingEvents", expression = "java(deserializeMissingEvents(entity.getMissingEvents()))")
    public abstract AttendanceRegularizationDto toDto(AttendanceRegularization entity);

    /**
     * Renders the named missing events for storage in the row's JSON column.
     *
     * @param events The event names to store, possibly null or empty.
     * @return The JSON array, or null where there is nothing to store or it could not be written.
     */
    public String serializeMissingEvents(List<String> events) {
        if (events == null || events.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads the named missing events back out of the row's JSON column.
     *
     * @param json The stored JSON, possibly null, blank or unreadable.
     * @return The event names, or an empty list where there are none or they could not be read.
     */
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
     *
     * @param events The corrected events submitted with the request.
     * @return The JSON array, or null where there is nothing to store or it could not be written.
     */
    public String serializeRequestedEvents(List<ClockEventCreationDto> events) {
        if (events == null || events.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads back the corrected clock events stored by {@link #serializeRequestedEvents}.
     *
     * @param json The stored JSON, possibly null, blank or unreadable.
     * @return The corrected events, or an empty list where there are none or they could not be read.
     */
    public List<ClockEventCreationDto> deserializeRequestedEvents(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<ClockEventCreationDto>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
