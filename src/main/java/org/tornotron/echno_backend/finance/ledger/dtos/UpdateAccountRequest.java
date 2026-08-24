package org.tornotron.echno_backend.finance.ledger.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Payload to edit an existing account in the chart of accounts.")
public record UpdateAccountRequest(

        @Schema(description = "Account code, unique within the tenant. Because postings resolve by "
                + "account id, changing the code is safe.", example = "1100")
        @NotBlank @Size(max = 20) String code,

        @Schema(description = "Account name.", example = "Cash and Cash Equivalents")
        @NotBlank @Size(max = 200) String name,

        @Schema(description = "Whether the account is active and available for posting.", example = "true")
        @NotNull Boolean active,

        @Schema(description = "Optional description of the account.", example = "Petty cash and bank balances")
        @Size(max = 500) String description,

        @Schema(description = "New parent account id. Omit to leave the parent unchanged. The parent must "
                + "be in the same tenant, share this account's type, and not create a cycle.",
                example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10")
        UUID parentId
) {}
