package org.tornotron.echno_backend.finance.ledger.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.finance.ledger.AccountType;

import java.util.List;
import java.util.UUID;

@Schema(description = "An account and its child accounts, forming one node of the chart of accounts tree.")
public record AccountTreeDto(
        @Schema(description = "Unique account id.", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        UUID id,

        @Schema(description = "Account code, unique within the tenant.", example = "1100")
        String code,

        @Schema(description = "Account name.", example = "Cash and Cash Equivalents")
        String name,

        @Schema(description = "Root account type this account belongs to.", example = "ASSET")
        AccountType type,

        @Schema(description = "Whether the account is active and available for posting.", example = "true")
        boolean active,

        @Schema(description = "Optional description of the account.", example = "Petty cash and bank balances")
        String description,

        @Schema(description = "Whether entries can be posted directly to this account. Only leaf accounts "
                + "are postable.", example = "true")
        boolean postable,

        @Schema(description = "Child accounts nested under this account.")
        List<AccountTreeDto> children
) {
}
