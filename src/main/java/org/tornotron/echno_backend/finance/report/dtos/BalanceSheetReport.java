package org.tornotron.echno_backend.finance.report.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Balance sheet as of a given date: assets, liabilities and equity, with their totals.")
public record BalanceSheetReport(
        @Schema(description = "Date the balance sheet is computed as of.", example = "2026-08-31")
        LocalDate asOfDate,
        @Schema(description = "Asset accounts with their closing balances.")
        List<AccountLine> assets,
        @Schema(description = "Sum of all asset account balances.", example = "18500000.00")
        BigDecimal totalAssets,
        @Schema(description = "Liability accounts with their closing balances.")
        List<AccountLine> liabilities,
        @Schema(description = "Sum of all liability account balances.", example = "6200000.00")
        BigDecimal totalLiabilities,
        @Schema(description = "Equity accounts with their closing balances.")
        List<AccountLine> equity,
        @Schema(description = "Sum of all equity account balances, excluding retained earnings for the period.", example = "9800000.00")
        BigDecimal totalEquity,
        @Schema(description = "Retained earnings accumulated for the reporting period.", example = "2500000.00")
        BigDecimal retainedEarningsForPeriod,
        @Schema(description = "Sum of total liabilities and total equity, expected to equal total assets.", example = "18500000.00")
        BigDecimal totalLiabilitiesAndEquity,
        @Schema(description = "Whether total assets equal total liabilities and equity.", example = "true")
        boolean balanced
) {
    @Schema(description = "A single account line on the balance sheet.")
    public record AccountLine(
            @Schema(description = "Account code.", example = "1100")
            String accountCode,
            @Schema(description = "Account name.", example = "Accounts Receivable")
            String accountName,
            @Schema(description = "Closing balance for the account.", example = "1250000.00")
            BigDecimal amount) {}
}
