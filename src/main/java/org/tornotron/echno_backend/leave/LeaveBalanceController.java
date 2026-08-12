package org.tornotron.echno_backend.leave;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceAdjustmentDto;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceDto;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceSummaryDto;
import org.tornotron.echno_backend.leave.dto.LeaveTransactionDto;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/leave-balances")
@Validated
public class LeaveBalanceController {

    private final LeaveBalanceService balanceService;

    public LeaveBalanceController(LeaveBalanceService balanceService) {
        this.balanceService = balanceService;
    }

    @GetMapping("/employee/{employeeId}")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LeaveBalanceDto>> getEmployeeBalances(
            @PathVariable Long employeeId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(balanceService.getAllBalancesForEmployee(employeeId, targetYear));
    }

    @GetMapping("/employee/{employeeId}/policy/{policyId}")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeaveBalanceDto> getSpecificBalance(
            @PathVariable Long employeeId,
            @PathVariable Long policyId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(balanceService.getOrCalculateBalance(employeeId, policyId, targetYear));
    }

    @GetMapping("/employee/{employeeId}/summary")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<LeaveBalanceSummaryDto> getBalanceSummary(
            @PathVariable Long employeeId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(balanceService.getBalanceSummary(employeeId, targetYear));
    }

    @PostMapping("/employee/{employeeId}/recalculate")
//    @PreAuthorize("hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LeaveBalanceDto>> recalculateBalances(
            @PathVariable Long employeeId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(balanceService.getAllBalancesForEmployee(employeeId, targetYear));
    }

    @PostMapping("/adjust")
//    @PreAuthorize("hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeaveTransactionDto> adjustBalance(
            @Valid @RequestBody LeaveBalanceAdjustmentDto dto) {
        return ResponseEntity.ok(balanceService.adjustBalance(dto));
    }

    @GetMapping("/employee/{employeeId}/transactions")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.isSelfInCurrentTenant(#employeeId)")
    public ResponseEntity<List<LeaveTransactionDto>> getTransactionHistory(
            @PathVariable Long employeeId) {
        return ResponseEntity.ok(balanceService.getTransactionHistory(employeeId));
    }

    @GetMapping("/{balanceId}/transactions")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LeaveTransactionDto>> getTransactionsByBalance(
            @PathVariable Long balanceId) {
        return ResponseEntity.ok(balanceService.getTransactionsByBalance(balanceId));
    }
}
