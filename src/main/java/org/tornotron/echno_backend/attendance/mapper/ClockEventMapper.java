package org.tornotron.echno_backend.attendance.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.dto.ClockEventDto;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;

/**
 * Maps {@link ClockEvent} to its DTO. Scalar fields copy by name; the attached photos map
 * through the shared {@link AttachmentMapper}, which signs their download URLs.
 *
 * <p>This class used to sign those URLs itself, from a second copy of the attachment conversion
 * that took {@code FileStorageService} as a parameter and threaded it down from the service. The
 * copy filled fewer fields than the shared mapper does, so the same file described one way when
 * it hung off a clock event and another way everywhere else. Delegating removes both the
 * duplicate and the parameter.
 */
@Mapper(componentModel = "spring", uses = AttachmentMapper.class)
public interface ClockEventMapper {

    /** The entity has no photo URL of its own; the photos are attachments. */
    @Mapping(target = "photoUrl", ignore = true)
    ClockEventDto toDto(ClockEvent entity);
}
