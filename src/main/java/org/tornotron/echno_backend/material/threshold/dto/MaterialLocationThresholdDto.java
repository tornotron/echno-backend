package org.tornotron.echno_backend.material.threshold.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "A material's planning thresholds overridden for a single storage location. "
        + "Any field left null falls back to the material's global level.")
@Data
public class MaterialLocationThresholdDto {

    @Schema(description = "Override id.", example = "7")
    private Long id;

    @Schema(description = "Id of the material this override applies to.", example = "12")
    private Long materialId;

    @Schema(description = "Id of the storage location this override applies to.", example = "2")
    private Long storageLocationId;

    @Schema(description = "Name of the storage location this override applies to.", example = "Main Store")
    private String storageLocationName;

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
