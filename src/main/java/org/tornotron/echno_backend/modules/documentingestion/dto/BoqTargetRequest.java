package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The sub-contract a package's BOQ is written to. Null clears it, and accepted rows stay as the package's tender BOQ.")
public record BoqTargetRequest(
        @Schema(description = "A sub-contract of the organization, in the package's project when the package has one", nullable = true)
        Long subContractId
) {}
