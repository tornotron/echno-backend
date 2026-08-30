package org.tornotron.echno_backend.attendance.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.tornotron.echno_backend.attendance.MovementRecord;
import org.tornotron.echno_backend.attendance.dto.MovementRecordDto;

import java.util.Collections;
import java.util.List;

/**
 * Maps {@link MovementRecord} to its DTO. The parent attendance flattens to its id and the
 * attachment list is held on the row as a JSON string, so it is read back here; everything else
 * copies by name.
 *
 * <p>Abstract class rather than interface because the JSON pair is real code: the write path
 * calls {@link #serializeAttachments} on the way in and the read path
 * {@link #deserializeAttachments} on the way out, and both belong next to each other.
 */
@Mapper(componentModel = "spring")
public abstract class MovementRecordMapper {

    @Autowired
    protected ObjectMapper objectMapper;

    @Mapping(source = "attendance.id", target = "attendanceId")
    @Mapping(target = "attachments", expression = "java(deserializeAttachments(entity.getAttachments()))")
    public abstract MovementRecordDto toDto(MovementRecord entity);

    /**
     * Renders the attachment references for storage in the row's JSON column.
     *
     * @param attachments The references to store, possibly null or empty.
     * @return The JSON array, or null where there is nothing to store or it could not be written.
     */
    public String serializeAttachments(List<String> attachments) {
        if (attachments == null || attachments.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(attachments);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads the attachment references back out of the row's JSON column.
     *
     * @param json The stored JSON, possibly null, blank or unreadable.
     * @return The references, or an empty list where there are none or they could not be read.
     */
    public List<String> deserializeAttachments(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
