package org.tornotron.echno_backend.material.threshold.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Payload to set or replace a material's thresholds at one storage location. "
        + "Every field is optional; a field left null clears that override so the material's global "
        + "level applies at the location.")
@Data
public class MaterialLocationThresholdUpsertDto {

    @Schema(description = "Minimum stock level at this location before the material is considered low.", example = "150")
    private Double minStock;

    @Schema(description = "Maximum stock level to hold at this location.", example = "1500")
    private Double maxStock;

    @Schema(description = "Safety stock buffer kept in reserve at this location.", example = "40")
    private Double safetyStock;

    @Schema(description = "Stock level at this location at which a reorder should be triggered.", example = "250")
    private Double reorderLevel;

    @Schema(description = "Minimum order quantity when replenishing this location.", example = "80")
    private Double moq;
}
