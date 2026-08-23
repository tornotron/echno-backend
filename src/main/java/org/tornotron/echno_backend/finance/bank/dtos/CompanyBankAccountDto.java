package org.tornotron.echno_backend.finance.bank.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "A company bank account, including the ledger account it reconciles against, as returned by the API.")
public record CompanyBankAccountDto(
        @Schema(description = "Unique id of the bank account record.", example = "9f1c3e2a-6b4d-4e7a-9c1b-2d3e4f5a6b7c")
        UUID id,
        @Schema(description = "Name of the bank.", example = "HDFC Bank")
        String bankName,
        @Schema(description = "Account number.", example = "50100234567890")
        String accountNumber,
        @Schema(description = "Name on the account.", example = "Fereydon Pvt Ltd")
        String accountHolderName,
        @Schema(description = "IFSC code of the branch.", example = "HDFC0001234")
        String ifscCode,
        @Schema(description = "SWIFT code, for international transfers.", example = "HDFCINBB")
        String swiftCode,
        @Schema(description = "Whether this is the default account for new transactions.", example = "true")
        boolean isDefault,
        @Schema(description = "Whether the account is currently active.", example = "true")
        boolean active,
        @Schema(description = "Id of the ledger account this bank account reconciles against.", example = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d")
        UUID ledgerAccountId,
        @Schema(description = "Code of the linked ledger account.", example = "1010")
        String ledgerAccountCode,
        @Schema(description = "Name of the linked ledger account.", example = "HDFC Current Account")
        String ledgerAccountName
) {}
