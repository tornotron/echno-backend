package org.tornotron.echno_backend.finance.bank.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateCompanyBankAccountRequest(
        @NotBlank @Size(max = 200) String bankName,
        @NotBlank @Size(max = 50) String accountNumber,
        @NotBlank @Size(max = 200) String accountHolderName,
        @Size(max = 20) String ifscCode,
        @Size(max = 20) String swiftCode,
        boolean isDefault,
        @NotNull UUID ledgerAccountId
) {}
