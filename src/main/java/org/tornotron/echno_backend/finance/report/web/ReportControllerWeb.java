package org.tornotron.echno_backend.finance.report.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.finance.report.dtos.BalanceSheetReport;
import org.tornotron.echno_backend.finance.report.dtos.ProfitAndLossReport;
import org.tornotron.echno_backend.finance.report.dtos.TrialBalanceReport;
import org.tornotron.echno_backend.finance.report.service.ReportService;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/finance/reports/web")
@RequiredArgsConstructor
@Tag(
        name = "Finance Reports",
        description = "Standard accounting reports computed from the ledger: trial balance, profit and "
                + "loss, and balance sheet. Each report is computed on demand from posted journal entries "
                + "for the requested date or date range. All endpoints are tenant scoped and limited to "
                + "system administrators and project managers."
)
public class ReportControllerWeb {

    private final ReportService service;

    @GetMapping("/trial-balance")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get the trial balance",
            description = "Returns the trial balance as of the given date: every account with its debit "
                    + "and credit totals, and whether the ledger balances."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trial balance computed and returned"),
            @ApiResponse(responseCode = "400", description = "asOfDate is missing or not a valid ISO date"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public TrialBalanceReport trialBalance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)LocalDate asOfDate
            ) {
        return service.trailBalanceReport(asOfDate);
    }

    @GetMapping("/profit-and-loss")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get the profit and loss statement",
            description = "Returns income and expense account totals between fromDate and toDate, and the "
                    + "resulting net profit."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profit and loss statement computed and returned"),
            @ApiResponse(responseCode = "400", description = "fromDate or toDate is missing, not a valid ISO date, or fromDate is after toDate"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ProfitAndLossReport profitAndLoss(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return service.profitAndLoss(fromDate, toDate);
    }

    @GetMapping("/balance-sheet")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get the balance sheet",
            description = "Returns assets, liabilities and equity as of the given date, with their totals "
                    + "and whether the balance sheet balances."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Balance sheet computed and returned"),
            @ApiResponse(responseCode = "400", description = "asOfDate is missing or not a valid ISO date"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public BalanceSheetReport balanceSheet(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        return service.balanceSheet(asOfDate);
    }
}
