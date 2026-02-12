package org.tornotron.echno_backend.leave;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.leave.dto.LeaveRequestCreationDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/leave-requests/web")
@Validated
public class LeaveRequestControllerWeb {

    private final LeaveRequestService requestService;


    public LeaveRequestControllerWeb(LeaveRequestService requestService) {
        this.requestService = requestService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<LeaveRequestDto> createRequest(
            @RequestParam Long employeeId,
            @Valid @RequestBody LeaveRequestCreationDto dto) {
        LeaveRequestDto created = requestService.createRequest(dto,employeeId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/request")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeaveRequestDto> getRequest(@RequestParam Long requestId) {
        return ResponseEntity.ok(requestService.getRequest(requestId));
    }

    @GetMapping("/employee")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LeaveRequestDto>> getEmployeeRequests(
            @RequestParam Long employeeId,
            Pageable pageable) {
        return ResponseEntity.ok(requestService.getRequestsByEmployee(employeeId, pageable).getContent());
    }

    @GetMapping("/employee-by-status")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LeaveRequestDto>> getEmployeeRequestsByStatus(
            @RequestParam Long employeeId,
            @RequestParam LeaveStatus status) {
        return ResponseEntity.ok(requestService.getRequestsByEmployeeAndStatus(employeeId, status));
    }

//    @GetMapping("/organization")
//    @PreAuthorize()
//    public ResponseEntity<List<LeaveRequestDto>> getOrganizationRequests(
//            @RequestParam Long organizationId,
//            Pageable pageable) {
//        return ResponseEntity.ok(requestService.getRequestsByOrganization(organizationId, pageable).getContent());
//    }

    @GetMapping("/pending-approvals")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admim','hr-admin')")
    public ResponseEntity<List<LeaveRequestDto>> getPendingApprovals(
            @RequestParam Long approverId,
            Pageable pageable) {
        return ResponseEntity.ok(requestService.getPendingApprovals(approverId, pageable).getContent());
    }

    @GetMapping("/pending-approvals/count")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<Map<String, Long>> getPendingApprovalCount(
            @RequestParam Long approverId) {
        long count = requestService.getPendingApprovalCount(approverId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PatchMapping("/update")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeaveRequestDto> updateRequest(
            @RequestParam Long requestId,
            @RequestBody Map<String, Object> updates) {
        return ResponseEntity.ok(requestService.updateRequest(requestId, updates));
    }

    @PostMapping("employeeId/{employeeId}/submit")
    @PreAuthorize("@orgSecurity.isSelfInCurrentTenant(#employeeId)")
    public ResponseEntity<LeaveRequestDto> submitRequest(@RequestParam Long requestId,@PathVariable Long employeeId) {
        return ResponseEntity.ok(requestService.submitRequest(requestId));
    }

    @PostMapping("/cancel")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeaveRequestDto> cancelRequest(
            @RequestParam Long requestId,
            @RequestBody Map<String, String> body) {
        String reason = body.get("reason");
        return ResponseEntity.ok(requestService.cancelRequest(requestId, reason));
    }

    @PostMapping("employeeId/{employeeId}/withdraw")
    @PreAuthorize("@orgSecurity.isSelfInCurrentTenant(#employeeId)")
    public ResponseEntity<LeaveRequestDto> withdrawRequest(@RequestParam Long requestId,@PathVariable Long employeeId) {
        return ResponseEntity.ok(requestService.withdrawRequest(requestId));
    }

    @GetMapping("/conflicts")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LocalDate>> getConflictingDates(
            @RequestParam Long employeeId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(requestService.getConflictingDates(employeeId, startDate, endDate));
    }

    @PostMapping("/calculate-days")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<Map<String, Double>> calculateDays(
            @RequestBody Map<String, String> body) {
        LocalDate startDate = LocalDate.parse(body.get("startDate"));
        LocalDate endDate = LocalDate.parse(body.get("endDate"));
        String startType = body.get("startHalfDayType");
        String endType = body.get("endHalfDayType");

        double totalDays = requestService.calculateTotalDays(
                startDate,
                startType != null ? org.tornotron.echno_backend.leave.enums.HalfDayType.valueOf(startType) : null,
                endDate,
                endType != null ? org.tornotron.echno_backend.leave.enums.HalfDayType.valueOf(endType) : null);

        return ResponseEntity.ok(Map.of("totalDays", totalDays));
    }
}
