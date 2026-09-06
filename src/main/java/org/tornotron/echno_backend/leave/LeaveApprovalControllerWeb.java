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
 * Web-console twin of {@link LeaveApprovalController}, addressing the request by a query parameter.
 *
 * <p>It carried the same role gate as its twin's approve endpoint, with the same effect: an
 * approval chain is built by walking the employee's management line, so the people who hold these
 * decisions are managers rather than administrators, and a manager holds neither system-admin nor
 * hr-admin. The guard refused them and the service refused any administrator who was not in the
 * chain, so the two halves agreed only by accident. Both twins are now gated on tenant membership,
 * with who may act settled in {@code LeaveApprovalService} against the record.
 */
@RestController
@RequestMapping("/api/v1/leave-approvals/web")
@Validated
@Tag(
        name = "Leave Approvals (Web)",
        description = "Web-console equivalent of the leave approval endpoints, addressing the request by a "
                + "requestId query parameter instead of a path segment. Covers approve, reject, delegate, "
                + "approval history and chain, and the can-approve check. Every endpoint is open to a "
                + "member of the caller's tenant, and who may act is settled against the request itself: "
                + "acting on one is the current approver's to do, and the approval trail is readable by the "
                + "employee the leave belongs to, the approvers in its chain, and the system-admin or "
                + "hr-admin roles."
)
public class LeaveApprovalControllerWeb {

    private final LeaveApprovalService approvalService;

    public LeaveApprovalControllerWeb(LeaveApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/approve")
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
            @RequestParam Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.approve(requestId, dto));
    }

    @PostMapping("/reject")
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
            @RequestParam Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.reject(requestId, dto));
    }

    @PostMapping("/delegate")
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
            @RequestParam Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.delegate(requestId, dto));
    }

    @GetMapping("/history")
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
            @RequestParam Long requestId) {
        return ResponseEntity.ok(approvalService.getApprovalHistory(requestId));
    }

    @GetMapping("/chain")
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
            @RequestParam Long requestId) {
        return ResponseEntity.ok(approvalService.getApprovalChain(requestId));
    }

    @GetMapping("/can-approve")
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
            @RequestParam Long requestId) {
        boolean canApprove = approvalService.canApprove(requestId);
        return ResponseEntity.ok(Map.of("canApprove", canApprove));
    }
}
