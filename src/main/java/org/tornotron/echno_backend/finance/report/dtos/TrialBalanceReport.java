package org.tornotron.echno_backend.finance.report.dtos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TrialBalanceReport(
        LocalDate asOfDate,
        List<TrialBalanceRow> rows,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        boolean balanced
) {
}
