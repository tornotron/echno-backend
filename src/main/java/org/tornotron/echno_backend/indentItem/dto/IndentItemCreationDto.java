package org.tornotron.echno_backend.indentItem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Schema(description = "Payload to request a material item on an indent.")
@Data
public class IndentItemCreationDto {

    @Schema(description = "Id of the indent this item belongs to.", example = "42")
    private Long indentId;

    @Schema(description = "Id of the material being requested.", example = "44")
    @NotNull(message = "Material ID is required")
    private Long materialId;

    @Schema(description = "Additional specifications for the material.", example = "IS 1786 grade Fe 500D")
    private String additionalSpecifications;

    @Schema(description = "Quantity requested.", example = "500")
    @NotNull(message = "Requested quantity is required")
    @Positive(message = "Requested quantity must be positive")
    private Integer requestedQuantity;

    @Schema(description = "Quantity actually ordered, once a purchase order is raised.", example = "500")
    private Integer orderedQuantity;

    @Schema(description = "Free-text remarks on this item.", example = "Needed before slab pour on 2026-02-08")
    private String remarks;
}
