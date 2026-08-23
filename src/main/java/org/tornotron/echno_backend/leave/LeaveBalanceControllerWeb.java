package org.tornotron.echno_backend.leave;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Leave Balances (Web)",
        description = "Web-console equivalent of the leave balance endpoints, addressing the employee and "
                + "policy by query parameters instead of path segments. Covers reading balances and "
                + "summaries, recalculating and manually adjusting a balance, and reading the transaction "
                + "history. Reads that target another employee are gated to the system-admin or hr-admin "
                + "role, or to the caller acting on their own record."
)
public class LeaveBalanceControllerWeb {

    private final LeaveBalanceService balanceService;

    public LeaveBalanceControllerWeb(LeaveBalanceService balanceService) {
        this.balanceService = balanceService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "List an employee's leave balances",
            description = "Returns every leave policy balance held by the employee for the given year, "
                    + "defaulting to the current year when none is given."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Balances returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<List<LeaveBalanceDto>> getEmployeeBalances(
            @RequestParam Long employeeId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(balanceService.getAllBalancesForEmployee(employeeId, targetYear));
    }

    @GetMapping("/specific")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get an employee's balance for one policy",
            description = "Returns, or calculates on demand, the employee's balance under the given leave "
                    + "policy for the given year, defaulting to the current year when none is given."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Balance returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee or leave policy with the given id")
    })
    public ResponseEntity<LeaveBalanceDto> getSpecificBalance(
            @RequestParam Long employeeId,
            @RequestParam Long policyId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(balanceService.getOrCalculateBalance(employeeId, policyId, targetYear));
    }

    @GetMapping("/summary")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get an employee's balance summary",
            description = "Returns the employee's balances for the given year together with totals for "
                    + "available, used and pending days across all policies."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Summary returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<LeaveBalanceSummaryDto> getBalanceSummary(
            @RequestParam Long employeeId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(balanceService.getBalanceSummary(employeeId, targetYear));
    }

    @PostMapping("/recalculate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Recalculate an employee's leave balances",
            description = "Recomputes every policy balance held by the employee for the given year and "
                    + "returns the refreshed list, defaulting to the current year when none is given."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Balances recalculated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<List<LeaveBalanceDto>> recalculateBalances(
            @RequestParam Long employeeId,
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(balanceService.getAllBalancesForEmployee(employeeId, targetYear));
    }

    @PostMapping("/adjust")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Manually adjust a leave balance",
            description = "Applies a signed day adjustment to an employee's balance under the given policy "
                    + "and records the reason. Returns the resulting transaction."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Adjustment applied"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The adjustment payload failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee or leave policy with the given id")
    })
    public ResponseEntity<LeaveTransactionDto> adjustBalance(
            @Valid @RequestBody LeaveBalanceAdjustmentDto dto) {
        return ResponseEntity.ok(balanceService.adjustBalance(dto));
    }

    @GetMapping("/transactions")
    @PreAuthorize("@orgSecurity.isSelfInCurrentTenant(#employeeId)")
    @Operation(
            summary = "Get an employee's transaction history",
            description = "Returns every leave balance transaction recorded for the employee, across all "
                    + "policies, in chronological order."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Transaction history returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the employee identified by the id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<List<LeaveTransactionDto>> getTransactionHistory(
            @RequestParam Long employeeId) {
        return ResponseEntity.ok(balanceService.getTransactionHistory(employeeId));
    }

    @GetMapping("/transactions-by-balance")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get transactions for one balance",
            description = "Returns every transaction recorded against the given leave balance record, in "
                    + "chronological order."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Transactions returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave balance with the given id")
    })
    public ResponseEntity<List<LeaveTransactionDto>> getTransactionsByBalance(
            @RequestParam Long balanceId) {
        return ResponseEntity.ok(balanceService.getTransactionsByBalance(balanceId));
    }
}
