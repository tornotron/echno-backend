package org.tornotron.echno_backend.finance.report.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Trial balance as of a given date: every account with its debit and credit totals.")
public record TrialBalanceReport(
        @Schema(description = "Date the trial balance is computed as of.", example = "2026-08-31")
        LocalDate asOfDate,
        @Schema(description = "One row per account with a non-zero balance.")
        List<TrialBalanceRow> rows,
        @Schema(description = "Sum of debit balances across all accounts.", example = "18500000.00")
        BigDecimal totalDebit,
        @Schema(description = "Sum of credit balances across all accounts.", example = "18500000.00")
        BigDecimal totalCredit,
        @Schema(description = "Whether total debits equal total credits.", example = "true")
        boolean balanced
) {
}
