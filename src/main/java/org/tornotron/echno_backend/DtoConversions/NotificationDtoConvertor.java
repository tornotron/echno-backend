package org.tornotron.echno_backend.DtoConversions;

import org.tornotron.echno_backend.leave.Notification;
import org.tornotron.echno_backend.leave.dto.NotificationDto;

public class NotificationDtoConvertor {

    public static NotificationDto convertToDto(Notification notification) {
        if (notification == null) return null;

        NotificationDto dto = new NotificationDto();
        dto.setId(notification.getId());
        dto.setRecipientId(notification.getRecipient().getId());
        dto.setNotificationType(notification.getNotificationType());
        dto.setTitle(notification.getTitle());
        dto.setMessage(notification.getMessage());
        dto.setEntityType(notification.getEntityType());
        dto.setEntityId(notification.getEntityId());
        dto.setActionUrl(notification.getActionUrl());
        dto.setIsRead(notification.getIsRead());
        dto.setReadAt(notification.getReadAt());
        dto.setCreatedAt(notification.getCreatedAt());
        return dto;
    }
}
