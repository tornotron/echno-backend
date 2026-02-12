package org.tornotron.echno_backend.leave;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.leave.dto.NotificationDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications/web")
@Validated
public class NotificationControllerWeb {

    private final NotificationService notificationService;

    public NotificationControllerWeb(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<Page<NotificationDto>> getNotifications(
            @RequestParam Long employeeId,
            Pageable pageable) {
        return ResponseEntity.ok(notificationService.getNotifications(employeeId, pageable));
    }

    @GetMapping("/unread")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<NotificationDto>> getUnreadNotifications(
            @RequestParam Long employeeId) {
        return ResponseEntity.ok(notificationService.getUnreadNotifications(employeeId));
    }

    @GetMapping("/unread-count")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<Map<String, Long>> getUnreadCount(
            @RequestParam Long employeeId) {
        long count = notificationService.getUnreadCount(employeeId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PatchMapping("/read")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<ApiResponse> markAsRead(@RequestParam Long notificationId) {
        notificationService.markAsRead(notificationId);
        return ResponseEntity.ok(new ApiResponse("Notification marked as read"));
    }

    @PostMapping("/mark-all-read")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<Map<String, Integer>> markAllAsRead(
            @RequestParam Long employeeId) {
        int count = notificationService.markAllAsRead(employeeId);
        return ResponseEntity.ok(Map.of("markedAsRead", count));
    }
}
