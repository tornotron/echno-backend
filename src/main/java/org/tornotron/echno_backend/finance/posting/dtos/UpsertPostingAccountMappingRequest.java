package org.tornotron.echno_backend.finance.posting.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Payload to point a posting role at a specific account.")
public record UpsertPostingAccountMappingRequest(

        @Schema(description = "Id of the account, which must be a postable leaf account in the current "
                + "tenant.", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        @NotNull(message = "accountId is required")
        UUID accountId
) {}
