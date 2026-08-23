package org.tornotron.echno_backend.finance.ledger.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.finance.ledger.AccountType;

import java.util.UUID;

@Schema(description = "An account in the chart of accounts, with its code, type and place in the hierarchy.")
public record AccountDto(
        @Schema(description = "Unique account id.", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        UUID id,

        @Schema(description = "Account code, unique within the tenant.", example = "1100")
        String code,

        @Schema(description = "Account name.", example = "Cash and Cash Equivalents")
        String name,

        @Schema(description = "Root account type this account belongs to.", example = "ASSET")
        AccountType type,

        @Schema(description = "Parent account id, or null for a root account.",
                example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10")
        UUID parentId,

        @Schema(description = "Whether the account is active and available for posting.", example = "true")
        boolean active,

        @Schema(description = "Optional description of the account.", example = "Petty cash and bank balances")
        String description
) {
}
