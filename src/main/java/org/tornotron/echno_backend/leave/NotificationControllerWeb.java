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

/**
 * Web-console equivalent of {@link NotificationController}, addressing a notification by a
 * {@code notificationId} query parameter instead of a path segment.
 *
 * <p>This twin was never phantom-guarded and so was never dead, which made it the reachable half
 * of the same fault. It asked for the system-admin or hr-admin role and then served whichever
 * employee the caller named, so an administrator read and cleared any colleague's inbox by passing
 * their id, and the approvers a leave chain is actually built from, who are line managers and hold
 * neither role, were refused the notification bell that tells them a request has arrived. Both
 * halves are the shape closed in #589, #599, #607, #631, #635 and #683.
 *
 * <p>Marking read was worse in kind. It took only a notification id and checked no ownership at
 * all, so any of the two roles' holders marked any colleague's notification read by counting ids,
 * with the effect that the recipient never saw it. That one needed a check against the stored row
 * rather than a repaired guard, and it has one now in {@code NotificationService.markAsRead}.
 *
 * <p>Both twins now answer the same question, "what is in my inbox", gated on membership. This is
 * narrower than the role gate it replaces, not wider: before, any holder of system-admin or
 * hr-admin reached every inbox in the tenant; now each caller reaches their own and nobody reaches
 * anybody else's. Dropping {@code employeeId} is safe on the deployed console, because Spring
 * ignores a query parameter no handler declares, so the call {@code echno-core} makes today is
 * served the caller's own inbox rather than refused.
 */
@RestController
@RequestMapping("/api/v1/notifications/web")
@Validated
@Tag(
        name = "Notifications (Web)",
        description = "Web-console equivalent of the notification endpoints, addressing a notification by "
                + "a notificationId query parameter instead of a path segment. Covers reading a page of "
                + "the caller's notifications, listing or counting the unread ones, and marking one or "
                + "all as read. An inbox is personal, so every endpoint answers about the caller and none "
                + "takes an employee id; access is gated on membership of the caller's current tenant."
)
public class NotificationControllerWeb {

    private final NotificationService notificationService;

    public NotificationControllerWeb(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List your notifications",
            description = "Returns a page of the notifications addressed to the signed-in caller, newest "
                    + "first. The recipient used to be a query parameter under a role gate that never "
                    + "read it; a caller that still sends employeeId is served their own inbox, because "
                    + "a query parameter no handler declares is ignored."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of notifications returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or has no employee record in it, so there is no inbox to serve")
    })
    public ResponseEntity<Page<NotificationDto>> getNotifications(Pageable pageable) {
        return ResponseEntity.ok(notificationService.getMyNotifications(pageable));
    }

    @GetMapping("/unread")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List your unread notifications",
            description = "Returns every notification addressed to the signed-in caller that has not yet "
                    + "been marked read."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Unread notifications returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or has no employee record in it, so there is no inbox to serve")
    })
    public ResponseEntity<List<NotificationDto>> getUnreadNotifications() {
        return ResponseEntity.ok(notificationService.getMyUnreadNotifications());
    }

    @GetMapping("/unread-count")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Count your unread notifications",
            description = "Returns the number of notifications addressed to the signed-in caller that "
                    + "have not yet been marked read, for the badge the console draws on the menu."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Count returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or has no employee record in it, so there is no inbox to count")
    })
    public ResponseEntity<Map<String, Long>> getUnreadCount() {
        long count = notificationService.getMyUnreadCount();
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PatchMapping("/read")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Mark one of your notifications as read",
            description = "Marks the given notification read and records the time it was read. Ownership "
                    + "is settled in NotificationService against the recipient stored on the row: this "
                    + "handler names no employee, so there is nothing here for a self-check to read. The "
                    + "recipient is the only person who may mark it, because the damage from marking "
                    + "somebody else's notification read is that they never see it."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Notification marked as read"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "The notification is addressed to somebody else, or the caller is not a member of the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No notification with the given id in this organization")
    })
    public ResponseEntity<ApiResponse> markAsRead(@RequestParam Long notificationId) {
        notificationService.markAsRead(notificationId);
        return ResponseEntity.ok(new ApiResponse("Notification marked as read"));
    }

    @PostMapping("/mark-all-read")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Mark all of your notifications as read",
            description = "Marks every unread notification addressed to the signed-in caller as read and "
                    + "returns how many were updated."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Notifications marked as read"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or has no employee record in it, so there is no inbox to update")
    })
    public ResponseEntity<Map<String, Integer>> markAllAsRead() {
        int count = notificationService.markAllAsRead();
        return ResponseEntity.ok(Map.of("markedAsRead", count));
    }
}
