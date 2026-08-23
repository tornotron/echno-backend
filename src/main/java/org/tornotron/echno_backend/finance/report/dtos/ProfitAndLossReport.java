package org.tornotron.echno_backend.finance.report.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Profit and loss statement for a date range: income and expense accounts with their totals and the resulting net profit.")
public record ProfitAndLossReport(
        @Schema(description = "Start of the reporting period, inclusive.", example = "2026-04-01")
        LocalDate fromDate,
        @Schema(description = "End of the reporting period, inclusive.", example = "2026-08-31")
        LocalDate toDate,
        @Schema(description = "Income accounts with their totals for the period.")
        List<AccountLine> income,
        @Schema(description = "Sum of all income for the period.", example = "9400000.00")
        BigDecimal totalIncome,
        @Schema(description = "Expense accounts with their totals for the period.")
        List<AccountLine> expense,
        @Schema(description = "Sum of all expenses for the period.", example = "6900000.00")
        BigDecimal totalExpense,
        @Schema(description = "Net profit for the period: total income minus total expense.", example = "2500000.00")
        BigDecimal netProfit
) {
    @Schema(description = "A single account line on the profit and loss statement.")
    public record AccountLine(
            @Schema(description = "Account code.", example = "4100")
            String accountCode,
            @Schema(description = "Account name.", example = "Contract Revenue")
            String accountName,
            @Schema(description = "Total for the account over the reporting period.", example = "7200000.00")
            BigDecimal amount) {}
}
