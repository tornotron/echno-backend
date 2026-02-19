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
@RequestMapping("/api/v1/leave-balances/web")
@Validated
public class LeaveBalanceControllerWeb {

    private final LeaveBalanceService balanceService;

    public LeaveBalanceControllerWeb(LeaveBalanceService balanceService) {
        this.balanceService = balanceService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LeaveBalanceDto>> getEmployeeBalances(
            @RequestParam Long employeeId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(balanceService.getAllBalancesForEmployee(employeeId, targetYear));
    }

    @GetMapping("/specific")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeaveBalanceDto> getSpecificBalance(
            @RequestParam Long employeeId,
            @RequestParam Long policyId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(balanceService.getOrCalculateBalance(employeeId, policyId, targetYear));
    }

    @GetMapping("/summary")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<LeaveBalanceSummaryDto> getBalanceSummary(
            @RequestParam Long employeeId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(balanceService.getBalanceSummary(employeeId, targetYear));
    }

    @PostMapping("/recalculate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LeaveBalanceDto>> recalculateBalances(
            @RequestParam Long employeeId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(balanceService.getAllBalancesForEmployee(employeeId, targetYear));
    }

    @PostMapping("/adjust")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeaveTransactionDto> adjustBalance(
            @Valid @RequestBody LeaveBalanceAdjustmentDto dto) {
        return ResponseEntity.ok(balanceService.adjustBalance(dto));
    }

    @GetMapping("/transactions")
    @PreAuthorize("@orgSecurity.isSelfInCurrentTenant(#employeeId)")
    public ResponseEntity<List<LeaveTransactionDto>> getTransactionHistory(
            @RequestParam Long employeeId) {
        return ResponseEntity.ok(balanceService.getTransactionHistory(employeeId));
    }

    @GetMapping("/transactions-by-balance")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LeaveTransactionDto>> getTransactionsByBalance(
            @RequestParam Long balanceId) {
        return ResponseEntity.ok(balanceService.getTransactionsByBalance(balanceId));
    }
}
