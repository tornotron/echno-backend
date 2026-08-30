package org.tornotron.echno_backend.leave;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.leave.dto.LeaveCancellationDto;
import org.tornotron.echno_backend.leave.dto.LeaveDaysCalculationDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestCreationDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestUpdateFieldsDto;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/leave-requests")
@Validated
@Tag(
        name = "Leave Requests",
        description = "The leave request lifecycle: draft creation, submission for approval, cancellation "
                + "and withdrawal, plus reads by request id, employee, status, organization or approver, "
                + "conflict checking and total-days calculation. Reads and updates on another employee's "
                + "requests are gated to the system-admin or hr-admin role; actions on a caller's own "
                + "request are gated to the caller being that employee."
)
public class LeaveRequestController {

    private final LeaveRequestService requestService;

    public LeaveRequestController(LeaveRequestService requestService) {
        this.requestService = requestService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.isSelfOrHasAnyOrgRole(#employeeId, 'system-admin', 'hr-admin')")
    @Operation(
            summary = "Create a leave request",
            description = "Creates a leave request for the given employee from the dates, policy and reason "
                    + "in the payload. Submitted immediately for approval when submitImmediately is true, "
                    + "otherwise saved as a draft."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Request created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The request payload failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee named nor a holder of the system-admin or hr-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The requested dates overlap an existing leave request, or the balance is insufficient")
    })
    public ResponseEntity<LeaveRequestDto> createRequest(
            @RequestParam Long employeeId,
            @Valid @RequestBody LeaveRequestCreationDto dto) {
        LeaveRequestDto created = requestService.createRequest(dto,employeeId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/requestId/{requestId}")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get a leave request by id",
            description = "Returns a single leave request with its approval trail."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Request found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id")
    })
    public ResponseEntity<LeaveRequestDto> getRequest(@PathVariable Long requestId) {
        return ResponseEntity.ok(requestService.getRequest(requestId));
    }

    @GetMapping("/employeeId/{employeeId}")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.isSelfInCurrentTenant(#employeeId)")
    @Operation(
            summary = "List an employee's leave requests",
            description = "Returns a page of leave requests raised by the employee, across all statuses."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of requests returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the employee identified by the id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<Page<LeaveRequestDto>> getEmployeeRequests(
            @PathVariable Long employeeId,
            Pageable pageable) {
        return ResponseEntity.ok(requestService.getRequestsByEmployee(employeeId, pageable));
    }

    @GetMapping("/employeeId/{employeeId}/status/{status}")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "List an employee's leave requests by status",
            description = "Returns the employee's leave requests filtered to the given status, for "
                    + "example PENDING_APPROVAL or APPROVED."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Requests returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<List<LeaveRequestDto>> getEmployeeRequestsByStatus(
            @PathVariable Long employeeId,
            @PathVariable LeaveStatus status) {
        return ResponseEntity.ok(requestService.getRequestsByEmployeeAndStatus(employeeId, status));
    }

    @GetMapping("/organizationId/{organizationId}")
//    @PreAuthorize("hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "List the current tenant's leave requests",
            description = "Returns every leave request raised within the caller's current tenant."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Requests returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<LeaveRequestDto>> getOrganizationRequests() {
        return ResponseEntity.ok(requestService.getRequestsByOrganization());
    }

    @GetMapping("/pending-approvals")
//    @PreAuthorize("hasAuthority('leave:approve') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "List requests pending an approver's action",
            description = "Returns the leave requests currently awaiting action from the given approver."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Pending requests returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No approver with the given id")
    })
    public ResponseEntity<List<LeaveRequestDto>> getPendingApprovals(
            @RequestParam Long approverId) {
        return ResponseEntity.ok(requestService.getPendingApprovals(approverId));
    }

    @GetMapping("/pending-approvals/count")
//    @PreAuthorize("hasAuthority('leave:approve') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Count requests pending an approver's action",
            description = "Returns the number of leave requests currently awaiting action from the given "
                    + "approver."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Count returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No approver with the given id")
    })
    public ResponseEntity<Map<String, Long>> getPendingApprovalCount(
            @RequestParam Long approverId) {
        long count = requestService.getPendingApprovalCount(approverId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PatchMapping("requestId/{requestId}")
    // Ownership is settled in LeaveRequestService against the request's own employee: this
    // handler takes no employee id, so there is nothing here for a self-check to read.
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Partially update a leave request",
            description = "Applies the given field updates to a draft leave request and returns the "
                    + "updated record."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Request updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the update fields failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee the request belongs to nor a holder of the system-admin or hr-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The updated dates overlap an existing leave request, or the request is no longer editable")
    })
    public ResponseEntity<LeaveRequestDto> updateRequest(
            @PathVariable Long requestId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(schema = @Schema(implementation = LeaveRequestUpdateFieldsDto.class)))
            @RequestBody Map<String, Object> updates) {
        return ResponseEntity.ok(requestService.updateRequest(requestId, updates));
    }

    @PostMapping("/requestId/{requestId}/submit")
    // Ownership is settled in LeaveRequestService against the request's own employee: this
    // handler takes no employee id, so there is nothing here for a self-check to read.
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Submit a leave request for approval",
            description = "Moves a draft leave request into the approval workflow, assigning the first "
                    + "approver in the chain."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Request submitted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee the request belongs to nor a holder of the system-admin or hr-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The request is not in a submittable state, or the balance is insufficient")
    })
    public ResponseEntity<LeaveRequestDto> submitRequest(@PathVariable Long requestId) {
        return ResponseEntity.ok(requestService.submitRequest(requestId));
    }

    @PostMapping("/requestId/{requestId}/cancel")
    // Ownership is settled in LeaveRequestService against the request's own employee: this
    // handler takes no employee id, so there is nothing here for a self-check to read.
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Cancel a leave request",
            description = "Cancels an already-approved leave request and records the given reason, "
                    + "reversing any balance already deducted."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Request cancelled"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee the request belongs to nor a holder of the system-admin or hr-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The request is not in a cancellable state")
    })
    public ResponseEntity<LeaveRequestDto> cancelRequest(
            @PathVariable Long requestId,
            @Valid @RequestBody LeaveCancellationDto dto) {
        return ResponseEntity.ok(requestService.cancelRequest(requestId, dto.getReason()));
    }

    @PostMapping("/requestId/{requestId}/withdraw")
    // Ownership is settled in LeaveRequestService against the request's own employee: this
    // handler takes no employee id, so there is nothing here for a self-check to read.
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Withdraw a leave request",
            description = "Withdraws a leave request that is still pending approval, before any approver "
                    + "has acted on it."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Request withdrawn"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee the request belongs to nor a holder of the system-admin or hr-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave request with the given id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The request is not in a withdrawable state")
    })
    public ResponseEntity<LeaveRequestDto> withdrawRequest(@PathVariable Long requestId) {
        return ResponseEntity.ok(requestService.withdrawRequest(requestId));
    }

    @GetMapping("/conflicts")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.isSelfInCurrentTenant(#employeeId)")
    @Operation(
            summary = "Get conflicting leave dates",
            description = "Returns the dates within the given range that already fall inside another leave "
                    + "request for the employee."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Conflicting dates returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the employee identified by the id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<List<LocalDate>> getConflictingDates(
            @RequestParam Long employeeId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(requestService.getConflictingDates(employeeId, startDate, endDate));
    }

    @PostMapping("/calculate-days")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Calculate total leave days",
            description = "Calculates the number of leave days between startDate and endDate, accounting "
                    + "for the optional half-day type at either end."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Total days calculated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "startDate or endDate is missing or not a valid date"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<Map<String, Double>> calculateDays(
            @Valid @RequestBody LeaveDaysCalculationDto dto) {
        double totalDays = requestService.calculateTotalDays(
                dto.getStartDate(),
                dto.getStartHalfDayType(),
                dto.getEndDate(),
                dto.getEndHalfDayType());

        return ResponseEntity.ok(Map.of("totalDays", totalDays));
    }
}
