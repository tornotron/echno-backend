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
@RequestMapping("/api/v1/leave-approvals/web")
@Validated
public class LeaveApprovalControllerWeb {

    private final LeaveApprovalService approvalService;

    public LeaveApprovalControllerWeb(LeaveApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/approve")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeaveRequestDto> approve(
            @RequestParam Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.approve(requestId, dto));
    }

    @PostMapping("/reject")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeaveRequestDto> reject(
            @RequestParam Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.reject(requestId, dto));
    }

    @PostMapping("/delegate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeaveRequestDto> delegate(
            @RequestParam Long requestId,
            @Valid @RequestBody LeaveApprovalActionDto dto) {
        return ResponseEntity.ok(approvalService.delegate(requestId, dto));
    }

    @GetMapping("/history")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LeaveApprovalDto>> getApprovalHistory(
            @RequestParam Long requestId) {
        return ResponseEntity.ok(approvalService.getApprovalHistory(requestId));
    }

    @GetMapping("/chain")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LeaveApprovalDto>> getApprovalChain(
            @RequestParam Long requestId) {
        return ResponseEntity.ok(approvalService.getApprovalChain(requestId));
    }

    @GetMapping("/can-approve")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<Map<String, Boolean>> canApprove(
            @RequestParam Long requestId,
            @RequestParam Long employeeId) {
        boolean canApprove = approvalService.canApprove(requestId, employeeId);
        return ResponseEntity.ok(Map.of("canApprove", canApprove));
    }
}
