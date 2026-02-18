package org.tornotron.echno_backend.leave;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.leave.dto.LeaveRequestCreationDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/leave-requests")
@Validated
public class LeaveRequestController {

    private final LeaveRequestService requestService;

    public LeaveRequestController(LeaveRequestService requestService) {
        this.requestService = requestService;
    }

    @PostMapping
//    @PreAuthorize("hasAuthority('leave:create') or hasAuthority('leave:admin')")
    public ResponseEntity<LeaveRequestDto> createRequest(
            @RequestParam Long employeeId,
            @Valid @RequestBody LeaveRequestCreationDto dto) {
        LeaveRequestDto created = requestService.createRequest(dto,employeeId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/requestId/{requestId}")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<LeaveRequestDto> getRequest(@PathVariable Long requestId) {
        return ResponseEntity.ok(requestService.getRequest(requestId));
    }

    @GetMapping("/employeeId/{employeeId}")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<Page<LeaveRequestDto>> getEmployeeRequests(
            @PathVariable Long employeeId,
            Pageable pageable) {
        return ResponseEntity.ok(requestService.getRequestsByEmployee(employeeId, pageable));
    }

    @GetMapping("/employeeId/{employeeId}/status/{status}")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LeaveRequestDto>> getEmployeeRequestsByStatus(
            @PathVariable Long employeeId,
            @PathVariable LeaveStatus status) {
        return ResponseEntity.ok(requestService.getRequestsByEmployeeAndStatus(employeeId, status));
    }

    @GetMapping("/organizationId/{organizationId}")
//    @PreAuthorize("hasAuthority('leave:admin')")
    public ResponseEntity<List<LeaveRequestDto>> getOrganizationRequests() {
        return ResponseEntity.ok(requestService.getRequestsByOrganization());
    }

    @GetMapping("/pending-approvals")
//    @PreAuthorize("hasAuthority('leave:approve') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LeaveRequestDto>> getPendingApprovals(
            @RequestParam Long approverId) {
        return ResponseEntity.ok(requestService.getPendingApprovals(approverId));
    }

    @GetMapping("/pending-approvals/count")
//    @PreAuthorize("hasAuthority('leave:approve') or hasAuthority('leave:admin')")
    public ResponseEntity<Map<String, Long>> getPendingApprovalCount(
            @RequestParam Long approverId) {
        long count = requestService.getPendingApprovalCount(approverId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PatchMapping("requestId/{requestId}")
//    @PreAuthorize("hasAuthority('leave:update') or hasAuthority('leave:admin')")
    public ResponseEntity<LeaveRequestDto> updateRequest(
            @PathVariable Long requestId,
            @RequestBody Map<String, Object> updates) {
        return ResponseEntity.ok(requestService.updateRequest(requestId, updates));
    }

    @PostMapping("/requestId/{requestId}/submit")
//    @PreAuthorize("hasAuthority('leave:create') or hasAuthority('leave:admin')")
    public ResponseEntity<LeaveRequestDto> submitRequest(@PathVariable Long requestId) {
        return ResponseEntity.ok(requestService.submitRequest(requestId));
    }

    @PostMapping("/requestId/{requestId}/cancel")
//    @PreAuthorize("hasAuthority('leave:update') or hasAuthority('leave:admin')")
    public ResponseEntity<LeaveRequestDto> cancelRequest(
            @PathVariable Long requestId,
            @RequestBody Map<String, String> body) {
        String reason = body.get("reason");
        return ResponseEntity.ok(requestService.cancelRequest(requestId, reason));
    }

    @PostMapping("/requestId/{requestId}/withdraw")
//    @PreAuthorize("hasAuthority('leave:update') or hasAuthority('leave:admin')")
    public ResponseEntity<LeaveRequestDto> withdrawRequest(@PathVariable Long requestId) {
        return ResponseEntity.ok(requestService.withdrawRequest(requestId));
    }

    @GetMapping("/conflicts")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LocalDate>> getConflictingDates(
            @RequestParam Long employeeId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(requestService.getConflictingDates(employeeId, startDate, endDate));
    }

    @PostMapping("/calculate-days")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
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
