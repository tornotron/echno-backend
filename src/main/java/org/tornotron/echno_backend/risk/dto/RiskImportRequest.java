package org.tornotron.echno_backend.risk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Risks carried over from a browser's own storage, from before the register was kept on the
 * server. Added in the order given, each with the next R-number.
 */
@Schema(description = "Risks to import into a project's register in one go, in the order given.")
public record RiskImportRequest(
        @Schema(description = "The risks, at most 500.")
        @NotNull(message = "risks is required")
        @Size(min = 1, max = 500, message = "risks must hold between 1 and 500 entries")
        List<@Valid RiskRequest> risks) {
}
