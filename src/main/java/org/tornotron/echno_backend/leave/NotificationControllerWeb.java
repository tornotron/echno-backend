package org.tornotron.echno_backend.leave;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Notifications (Web)",
        description = "Web-console equivalent of the notification endpoints, addressing a notification by "
                + "a notificationId query parameter instead of a path segment. Covers reading a page of "
                + "notifications, listing or counting the unread ones, and marking one or all as read. "
                + "All endpoints are gated to the system-admin or hr-admin role in the caller's tenant."
)
public class NotificationControllerWeb {

    private final NotificationService notificationService;

    public NotificationControllerWeb(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "List an employee's notifications",
            description = "Returns a page of notifications addressed to the given employee, newest first."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of notifications returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<NotificationDto>> getNotifications(
            @RequestParam Long employeeId,
            Pageable pageable) {
        return ResponseEntity.ok(notificationService.getNotifications(employeeId, pageable));
    }

    @GetMapping("/unread")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "List an employee's unread notifications",
            description = "Returns every notification addressed to the given employee that has not yet "
                    + "been marked read."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Unread notifications returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<NotificationDto>> getUnreadNotifications(
            @RequestParam Long employeeId) {
        return ResponseEntity.ok(notificationService.getUnreadNotifications(employeeId));
    }

    @GetMapping("/unread-count")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Count an employee's unread notifications",
            description = "Returns the number of notifications addressed to the given employee that have "
                    + "not yet been marked read."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Count returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Map<String, Long>> getUnreadCount(
            @RequestParam Long employeeId) {
        long count = notificationService.getUnreadCount(employeeId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PatchMapping("/read")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Mark a notification as read",
            description = "Marks the given notification read and records the time it was read."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Notification marked as read"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No notification with the given id")
    })
    public ResponseEntity<ApiResponse> markAsRead(@RequestParam Long notificationId) {
        notificationService.markAsRead(notificationId);
        return ResponseEntity.ok(new ApiResponse("Notification marked as read"));
    }

    @PostMapping("/mark-all-read")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Mark all of an employee's notifications as read",
            description = "Marks every unread notification addressed to the given employee as read and "
                    + "returns how many were updated."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Notifications marked as read"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Map<String, Integer>> markAllAsRead(
            @RequestParam Long employeeId) {
        int count = notificationService.markAllAsRead(employeeId);
        return ResponseEntity.ok(Map.of("markedAsRead", count));
    }
}
