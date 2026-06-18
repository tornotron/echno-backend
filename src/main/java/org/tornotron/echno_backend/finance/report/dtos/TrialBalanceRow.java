package org.tornotron.echno_backend.finance.report.dtos;

import org.tornotron.echno_backend.finance.ledger.AccountType;

import java.math.BigDecimal;

public record TrialBalanceRow(
        String accountCode,
        String accountName,
        AccountType type,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal debitBalance,
        BigDecimal creditBalance
) {
}
