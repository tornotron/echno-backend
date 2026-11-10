package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** The project risk a contract term or obligation raised, and whether this call created it. */
@Schema(description = "The project risk raised from a contract term or an obligation. Raising again returns the "
        + "same risk with created false.")
public record RaisedRiskDto(
        UUID riskId,
        Long projectId,
        @Schema(example = "R-007")
        String riskNumber,
        @Schema(description = "True when this call added the risk; false when it was raised before.")
        boolean created) {
}
