package org.tornotron.echno_backend.finance.bank.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Payload to register a new company bank account.")
public record CreateCompanyBankAccountRequest(
        @Schema(description = "Name of the bank.", example = "HDFC Bank")
        @NotBlank @Size(max = 200) String bankName,
        @Schema(description = "Account number.", example = "50100234567890")
        @NotBlank @Size(max = 50) String accountNumber,
        @Schema(description = "Name on the account.", example = "Fereydon Pvt Ltd")
        @NotBlank @Size(max = 200) String accountHolderName,
        @Schema(description = "IFSC code of the branch.", example = "HDFC0001234")
        @Size(max = 20) String ifscCode,
        @Schema(description = "SWIFT code, for international transfers.", example = "HDFCINBB")
        @Size(max = 20) String swiftCode,
        @Schema(description = "Whether this becomes the default account for new transactions.", example = "true")
        boolean isDefault,
        @Schema(description = "Id of the ledger account this bank account reconciles against.", example = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d")
        @NotNull UUID ledgerAccountId
) {}
