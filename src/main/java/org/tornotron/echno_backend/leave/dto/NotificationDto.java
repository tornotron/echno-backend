package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.leave.enums.NotificationType;

import java.time.LocalDateTime;

@Schema(description = "An in-app notification raised by an event in the leave workflow.")
@Data
public class NotificationDto {
    @Schema(description = "Id of the notification.", example = "7710")
    private Long id;

    @Schema(description = "Id of the employee the notification is addressed to.", example = "5")
    private Long recipientId;

    @Schema(description = "Type of event that raised the notification.", example = "LEAVE_PENDING_APPROVAL")
    private NotificationType notificationType;

    @Schema(description = "Short title of the notification.", example = "Leave request pending your approval")
    private String title;

    @Schema(description = "Full notification message.", example = "Ravi Kumar has requested 2.5 days of "
            + "Casual Leave from 2026-09-14 to 2026-09-16")
    private String message;

    @Schema(nullable = true, description = "Type of the entity this notification refers to. The leave "
            + "senders always set it, but a notification raised through the generic delivery seam carries "
            + "whatever the sender put in the draft, which may be nothing.", example = "LEAVE_REQUEST")
    private String entityType;

    @Schema(nullable = true, description = "Id of the entity this notification refers to. Null whenever "
            + "entityType is, since the two are set together.", example = "241")
    private Long entityId;

    @Schema(nullable = true, description = "URL the client should navigate to when the notification is "
            + "opened. Null where the sender supplied none, leaving the notification with nothing to open.",
            example = "/leave-requests/241")
    private String actionUrl;

    @Schema(description = "Whether the notification has been read.", example = "false")
    private Boolean isRead;

    @Schema(nullable = true, description = "Time the notification was read. Null until it is marked read.",
            example = "2026-08-31T09:10:00")
    private LocalDateTime readAt;

    @Schema(description = "Time the notification was created.", example = "2026-08-30T09:16:00")
    private LocalDateTime createdAt;
}
