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

@RestController
@RequestMapping("/api/v1/leave-approvals/web")
@Validated
@Tag(
        name = "Leave Approvals (Web)",
        description = "Web-console equivalent of the leave approval endpoints, addressing the request by a "
                + "requestId query parameter instead of a path segment. Covers approve, reject, delegate, "
                + "approval history and chain, and the can-approve check. All endpoints are gated to the "
                + "system-admin or hr-admin role in the caller's tenant."
)
public class LeaveApprovalControllerWeb {

    private final LeaveApprovalService approvalService;

    public LeaveApprovalControllerWeb(LeaveApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/approve")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Approve a leave request",
            description = "Records an approval for the given request at its current approval level and "
                    + "advances the workflow. Returns the request with its updated status and approval trail."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Request approved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The approval action payload failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id")
    })
    public ResponseEntity<LeaveRequestDto> approve(
            @RequestParam Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.approve(requestId, dto));
    }

    @PostMapping("/reject")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Reject a leave request",
            description = "Records a rejection for the given request, stopping the approval workflow. "
                    + "Returns the request with its updated status and approval trail."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Request rejected"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The approval action payload failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id")
    })
    public ResponseEntity<LeaveRequestDto> reject(
            @RequestParam Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.reject(requestId, dto));
    }

    @PostMapping("/delegate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Delegate a pending approval",
            description = "Reassigns the current approval step for the given request to the delegate named "
                    + "in the payload. Returns the request with its updated approver and approval trail."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approval delegated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The approval action payload failed validation, or no delegate was given"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id")
    })
    public ResponseEntity<LeaveRequestDto> delegate(
            @RequestParam Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.delegate(requestId, dto));
    }

    @GetMapping("/history")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get the approval history of a request",
            description = "Returns every approval action recorded against the given leave request, in the "
                    + "order they occurred."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approval history returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id")
    })
    public ResponseEntity<List<LeaveApprovalDto>> getApprovalHistory(
            @RequestParam Long requestId) {
        return ResponseEntity.ok(approvalService.getApprovalHistory(requestId));
    }

    @GetMapping("/chain")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get the approval chain of a request",
            description = "Returns the ordered sequence of approvers configured for the given leave request, "
                    + "including steps not yet acted on."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approval chain returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id")
    })
    public ResponseEntity<List<LeaveApprovalDto>> getApprovalChain(
            @RequestParam Long requestId) {
        return ResponseEntity.ok(approvalService.getApprovalChain(requestId));
    }

    @GetMapping("/can-approve")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Check whether an employee can approve a request",
            description = "Returns whether the given employee is the current approver for the given leave "
                    + "request, as a single boolean flag."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Eligibility flag returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id")
    })
    public ResponseEntity<Map<String, Boolean>> canApprove(
            @RequestParam Long requestId,
            @RequestParam Long employeeId) {
        boolean canApprove = approvalService.canApprove(requestId, employeeId);
        return ResponseEntity.ok(Map.of("canApprove", canApprove));
    }
}
