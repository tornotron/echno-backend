package org.tornotron.echno_backend.finance.report.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.finance.ledger.AccountType;

import java.math.BigDecimal;

@Schema(description = "A single account's row on the trial balance.")
public record TrialBalanceRow(
        @Schema(description = "Account code.", example = "2100")
        String accountCode,
        @Schema(description = "Account name.", example = "Accounts Payable")
        String accountName,
        @Schema(description = "Classification of the account.", example = "LIABILITY")
        AccountType type,
        @Schema(description = "Sum of all debit postings to the account.", example = "1800000.00")
        BigDecimal totalDebit,
        @Schema(description = "Sum of all credit postings to the account.", example = "2300000.00")
        BigDecimal totalCredit,
        @Schema(description = "Closing balance shown on the debit side, if the account is in debit.", example = "0.00")
        BigDecimal debitBalance,
        @Schema(description = "Closing balance shown on the credit side, if the account is in credit.", example = "500000.00")
        BigDecimal creditBalance
) {
}
