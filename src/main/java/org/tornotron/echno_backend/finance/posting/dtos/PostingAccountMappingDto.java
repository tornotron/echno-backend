package org.tornotron.echno_backend.finance.posting.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.finance.posting.PostingRole;
import org.tornotron.echno_backend.finance.posting.service.PostingAccountResolver;

import java.util.UUID;

@Schema(description = "A posting role and the account it currently resolves to for the tenant, "
        + "whether from an explicit mapping or the configured default.")
public record PostingAccountMappingDto(

        @Schema(description = "The posting role.", example = "ACCOUNTS_PAYABLE")
        PostingRole role,

        @Schema(description = "Where the effective account came from: an explicit per-org mapping, "
                + "or the configured default code.", example = "DEFAULT")
        PostingAccountResolver.Source source,

        @Schema(description = "Effective account id.", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        UUID accountId,

        @Schema(description = "Effective account code.", example = "2100")
        String accountCode,

        @Schema(description = "Effective account name.", example = "Accounts Payable")
        String accountName
) {}
