package org.tornotron.echno_backend.finance.report.dtos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProfitAndLossReport(
        LocalDate fromDate,
        LocalDate toDate,
        List<AccountLine> income,
        BigDecimal totalIncome,
        List<AccountLine> expense,
        BigDecimal totalExpense,
        BigDecimal netProfit
) {
    public record AccountLine(String accountCode, String accountName, BigDecimal amount) {}
}
