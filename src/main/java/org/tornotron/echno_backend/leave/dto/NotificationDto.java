package org.tornotron.echno_backend.leave.dto;

import lombok.Data;
import org.tornotron.echno_backend.leave.enums.NotificationType;

import java.time.LocalDateTime;

@Data
public class NotificationDto {
    private Long id;
    private Long recipientId;
    private NotificationType notificationType;
    private String title;
    private String message;
    private String entityType;
    private Long entityId;
    private String actionUrl;
    private Boolean isRead;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
