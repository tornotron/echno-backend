package org.tornotron.echno_backend.leave;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.leave.dto.LeaveApprovalActionDto;
import org.tornotron.echno_backend.leave.dto.LeaveApprovalDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;

import java.util.List;
import java.util.Map;

/**
 * The approval workflow of a submitted leave request, addressed by a path segment.
 *
 * <p>This is the surface a phone uses, and until now it did not work. Reject, delegate, the two
 * approval-trail reads and the can-approve check asked for {@code hasAuthority('leave:approve')},
 * {@code 'leave:read'} or {@code 'leave:admin'}. {@code JwtAuthConverter} mints a bare
 * {@code resource:scope} authority in exactly one place, {@code extractPermissions}, which reads
 * the {@code authorization} claim of an RPT, and the realm defines no authorization scopes at all
 * (traced end to end for the identical {@code organization:admin} case in the multi-tenancy audit
 * of 2026-08-18, and again for {@code billing:admin} in #641). Nothing granted those strings and
 * nothing could, so five of the six endpoints refused every caller while looking guarded.
 *
 * <p>Approve was the sixth, and it was the one with the subtler problem. Its guard asked for the
 * system-admin or hr-admin role, while {@code LeaveApprovalService} requires the caller to be the
 * request's current approver, and an approval chain is built by walking the employee's management
 * line. A site manager is an approver and holds neither role, so the guard refused them; a
 * system-admin who is not in the chain got past the guard and was refused by the service. The two
 * halves could only both be satisfied by the accident of an approver who also happened to be an
 * administrator.
 *
 * <p>So every endpoint here is now gated on tenant membership, which is what the annotation can
 * actually evaluate, and the decision of who may act is answered in the service against the
 * record: the current approver acts, and nobody else. That is narrower than the role gate it
 * replaces, not wider. The trail reads are answered the same way, against the chain.
 */
@RestController
@RequestMapping("/api/v1/leave-approvals")
@Validated
@Tag(
        name = "Leave Approvals",
        description = "Actions on the approval workflow of a submitted leave request: approve, reject, "
                + "delegate to another approver, and read the approval history or the full approval chain. "
                + "Every endpoint is open to a member of the caller's tenant, and who may act is settled "
                + "against the request itself: acting on one is the current approver's to do, and the "
                + "approval trail is readable by the employee the leave belongs to, the approvers in its "
                + "chain, and the system-admin or hr-admin roles."
)
public class LeaveApprovalController {

    private final LeaveApprovalService approvalService;

    public LeaveApprovalController(LeaveApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/requests/{requestId}/approve")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Approve a leave request",
            description = "Records an approval for the given request at its current approval level and "
                    + "advances the workflow. The approval is recorded against the signed-in caller, who "
                    + "must be the request's current approver. Returns the request with its updated status "
                    + "and approval trail."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Request approved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The approval action payload failed validation, or the caller is not the request's current approver, or the request is not pending approval"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or has no employee record in it, so the decision would name nobody"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id")
    })
    public ResponseEntity<LeaveRequestDto> approve(
            @PathVariable Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.approve(requestId, dto));
    }

    @PostMapping("/requests/{requestId}/reject")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Reject a leave request",
            description = "Records a rejection for the given request, stopping the approval workflow and "
                    + "releasing the days it was holding. The rejection is recorded against the signed-in "
                    + "caller, who must be the request's current approver. Returns the request with its "
                    + "updated status and approval trail."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Request rejected"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The approval action payload failed validation, or the caller is not the request's current approver, or the request is not pending approval"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or has no employee record in it, so the decision would name nobody"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id")
    })
    public ResponseEntity<LeaveRequestDto> reject(
            @PathVariable Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.reject(requestId, dto));
    }

    @PostMapping("/requests/{requestId}/delegate")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Delegate a pending approval",
            description = "Reassigns the current approval step for the given request to the delegate named "
                    + "in the payload, recording who handed it over. Only the request's current approver "
                    + "may delegate it. Returns the request with its updated approver and approval trail."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approval delegated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The approval action payload failed validation, no delegate was given, or the caller is not the request's current approver"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or has no employee record in it, so the handover would name nobody"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id, or no such delegate in this organization")
    })
    public ResponseEntity<LeaveRequestDto> delegate(
            @PathVariable Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.delegate(requestId, dto));
    }

    @GetMapping("/requests/{requestId}/history")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get the approval history of a request",
            description = "Returns every approval action recorded against the given leave request, in the "
                    + "order they occurred: who acted, at which level, with what comment, and when."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approval history returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or takes no part in this request and holds neither the system-admin nor the hr-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id")
    })
    public ResponseEntity<List<LeaveApprovalDto>> getApprovalHistory(
            @PathVariable Long requestId) {
        return ResponseEntity.ok(approvalService.getApprovalHistory(requestId));
    }

    @GetMapping("/requests/{requestId}/chain")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get the approval chain of a request",
            description = "Returns the ordered sequence of approvers configured for the given leave request, "
                    + "including steps not yet acted on."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approval chain returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or takes no part in this request and holds neither the system-admin nor the hr-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id")
    })
    public ResponseEntity<List<LeaveApprovalDto>> getApprovalChain(
            @PathVariable Long requestId) {
        return ResponseEntity.ok(approvalService.getApprovalChain(requestId));
    }

    @GetMapping("/requests/{requestId}/can-approve")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Check whether you can approve a request",
            description = "Returns whether the signed-in caller is the current approver for the given leave "
                    + "request, as a single boolean flag. This is the check a client makes before drawing "
                    + "an approve button. It used to take the employee to ask about as a query parameter, "
                    + "which let one employee probe another's place in a chain; a caller that still sends "
                    + "employeeId is answered for themselves, because a query parameter no handler declares "
                    + "is ignored."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Eligibility flag returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id")
    })
    public ResponseEntity<Map<String, Boolean>> canApprove(
            @PathVariable Long requestId) {
        boolean canApprove = approvalService.canApprove(requestId);
        return ResponseEntity.ok(Map.of("canApprove", canApprove));
    }
}
