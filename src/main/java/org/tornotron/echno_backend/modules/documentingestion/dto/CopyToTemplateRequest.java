package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Rows of the inspection and test plan to copy into the organization's checklist templates. Each row goes
 * into the template of its matched trade, or of {@code tradeId} for every row when one is given.
 */
@Schema(description = "Rows of the inspection and test plan to copy as check points into the checklist template of "
        + "their trade.")
public record CopyToTemplateRequest(
        @Schema(description = "Ids of rows of the inspection and test plan (not candidate ids).")
        @NotEmpty(message = "requirementIds must name at least one row")
        @Size(max = 200, message = "requirementIds may name at most 200 rows")
        List<UUID> requirementIds,

        @Schema(nullable = true, description = "The trade whose template every row goes into. Left out, each row "
                + "goes into the template of the trade it was matched to, and a row with no trade is skipped.")
        UUID tradeId) {
}
