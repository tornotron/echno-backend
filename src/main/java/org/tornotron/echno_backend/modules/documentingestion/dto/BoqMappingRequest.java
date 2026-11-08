package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

@Schema(description = "The column mapping a person confirms for one BOQ sheet. Reading the sheet again replaces its unapplied rows.")
public record BoqMappingRequest(
        @Schema(description = "The header row; data starts below it. 0 when the sheet has no header row", example = "2")
        @NotNull @Min(0) @Max(1_048_576) Integer headerRow,
        @Schema(description = "Field to 1-based column. description is required; itemCode, unit, quantity, rate and amount are optional",
                example = "{\"itemCode\":1,\"description\":2,\"unit\":3,\"quantity\":4,\"rate\":5,\"amount\":6}")
        @NotEmpty Map<String, @NotNull @Min(1) @Max(16_384) Integer> columns,
        @Schema(description = "Put before every item code, so sheets that each number from 1 can share a contract", example = "B2-", nullable = true)
        @Size(max = 20) String codePrefix
) {}
