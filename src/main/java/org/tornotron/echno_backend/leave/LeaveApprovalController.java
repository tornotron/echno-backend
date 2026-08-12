package org.tornotron.echno_backend.leave;

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
@RequestMapping("/api/v1/leave-approvals")
@Validated
public class LeaveApprovalController {

    private final LeaveApprovalService approvalService;

    public LeaveApprovalController(LeaveApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/requests/{requestId}/approve")
//    @PreAuthorize("hasAuthority('leave:approve') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeaveRequestDto> approve(
            @PathVariable Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.approve(requestId, dto));
    }

    @PostMapping("/requests/{requestId}/reject")
    @PreAuthorize("hasAuthority('leave:approve') or hasAuthority('leave:admin')")
    public ResponseEntity<LeaveRequestDto> reject(
            @PathVariable Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.reject(requestId, dto));
    }

    @PostMapping("/requests/{requestId}/delegate")
    @PreAuthorize("hasAuthority('leave:approve') or hasAuthority('leave:admin')")
    public ResponseEntity<LeaveRequestDto> delegate(
            @PathVariable Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.delegate(requestId, dto));
    }

    @GetMapping("/requests/{requestId}/history")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LeaveApprovalDto>> getApprovalHistory(
            @PathVariable Long requestId) {
        return ResponseEntity.ok(approvalService.getApprovalHistory(requestId));
    }

    @GetMapping("/requests/{requestId}/chain")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LeaveApprovalDto>> getApprovalChain(
            @PathVariable Long requestId) {
        return ResponseEntity.ok(approvalService.getApprovalChain(requestId));
    }

    @GetMapping("/requests/{requestId}/can-approve")
    @PreAuthorize("hasAuthority('leave:approve') or hasAuthority('leave:admin')")
    public ResponseEntity<Map<String, Boolean>> canApprove(
            @PathVariable Long requestId,
            @RequestParam Long employeeId) {
        boolean canApprove = approvalService.canApprove(requestId, employeeId);
        return ResponseEntity.ok(Map.of("canApprove", canApprove));
    }
}
