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
 * The signed-in caller's notification inbox.
 *
 * <p>Every endpoint here used to ask for {@code hasAuthority('leave:read')} or
 * {@code hasAuthority('leave:admin')}. {@code JwtAuthConverter} mints a bare
 * {@code resource:scope} authority in exactly one place, {@code extractPermissions}, which reads
 * the {@code authorization} claim of an RPT, so each of those strings needed a Keycloak
 * Authorization Services resource named {@code leave} carrying the matching scope. Nothing defines
 * one: the only automated provisioner, {@code KeycloakInitializer.ensureAuthorizationSetup},
 * creates a scopeless {@code Default Resource} and a scopeless {@code Default Permission}, and a
 * permission with no scopes yields no {@code resource:scope} authority at all. So the whole inbox
 * refused every caller, and had done since the annotations were written.
 *
 * <p>That mattered more than a dead read surface usually would. {@code LeaveApprovalService}
 * writes a notification on every routing step and every delegation, so the rows were being
 * created and nobody could read them: an approver had no way to learn a request was waiting on
 * them. The endpoints were repaired rather than removed, because the workflow they feed is
 * required.
 *
 * <p>Underneath the phantom sat a second defect. Four of the five took the recipient as an
 * {@code employeeId} query parameter that the guard never read, so repairing only the guard would
 * have handed whoever got through every colleague's inbox. A notification is addressed to one
 * person, so the recipient comes from the session and the parameter is gone. Removing it is safe
 * on a deployed client, because Spring ignores a query parameter no handler declares: a call that
 * still sends {@code employeeId} is served the caller's own inbox rather than refused.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Validated
@Tag(
        name = "Notifications",
        description = "The signed-in caller's own in-app notifications, raised by the leave workflow "
                + "when a request is submitted, approved, rejected or delegated. Endpoints cover reading "
                + "a page of them, listing or counting the unread ones, and marking one or all as read. "
                + "An inbox is personal, so every endpoint answers about the caller and none takes an "
                + "employee id; access is gated on membership of the caller's current tenant."
)
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List your notifications",
            description = "Returns a page of the notifications addressed to the signed-in caller, newest "
                    + "first. The recipient used to be a query parameter under a guard that only asked "
                    + "for an authority nothing grants; a caller that still sends employeeId is served "
                    + "their own inbox, because a query parameter no handler declares is ignored."
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
                    + "have not yet been marked read, for the badge a client draws on the menu."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Count returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or has no employee record in it, so there is no inbox to count")
    })
    public ResponseEntity<Map<String, Long>> getUnreadCount() {
        long count = notificationService.getMyUnreadCount();
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PatchMapping("/{notificationId}/read")
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
    public ResponseEntity<ApiResponse> markAsRead(@PathVariable Long notificationId) {
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
