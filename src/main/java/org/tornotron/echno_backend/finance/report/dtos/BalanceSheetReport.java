package org.tornotron.echno_backend.finance.report.dtos;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BalanceSheetReport(
        LocalDate asOfDate,
        List<AccountLine> assets,
        BigDecimal totalAssets,
        List<AccountLine> liabilities,
        BigDecimal totalLiabilities,
        List<AccountLine> equity,
        BigDecimal totalEquity,
        BigDecimal retainedEarningsForPeriod,
        BigDecimal totalLiabilitiesAndEquity,
        boolean balanced
) {
    public record AccountLine(String accountCode, String accountName, BigDecimal amount) {}
}
