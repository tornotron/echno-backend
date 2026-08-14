package org.tornotron.echno_backend.leave.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.leave.Notification;
import org.tornotron.echno_backend.leave.dto.NotificationDto;

/**
 * Maps {@link Notification} to its DTO. Every DTO field maps by name except the
 * recipient, which is flattened to its id.
 */
@Mapper(componentModel = "spring")
public interface NotificationMapper {

    @Mapping(source = "recipient.id", target = "recipientId")
    NotificationDto toDto(Notification notification);
}
