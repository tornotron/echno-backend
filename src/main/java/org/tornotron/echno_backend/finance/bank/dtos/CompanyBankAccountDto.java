package org.tornotron.echno_backend.finance.bank.dtos;

import java.util.UUID;

public record CompanyBankAccountDto(
        UUID id,
        String bankName,
        String accountNumber,
        String accountHolderName,
        String ifscCode,
        String swiftCode,
        boolean isDefault,
        boolean active,
        UUID ledgerAccountId,
        String ledgerAccountCode,
        String ledgerAccountName
) {}
