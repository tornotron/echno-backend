package org.tornotron.echno_backend.material.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "A material whose stock has reached or fallen below the reorder level in "
        + "force at the scope that was asked about.")
@Data
public class LowStockMaterialDto {

    @Schema(description = "Material id.", example = "12")
    private Long materialId;

    @Schema(description = "Stock keeping unit code.", example = "TMT-12MM-001")
    private String sku;

    @Schema(description = "Name of the material.", example = "TMT Bar 12mm")
    private String materialName;

    @Schema(description = "Unit of measure for the material.", example = "kg")
    private String unit;

    @Schema(description = "Quantity on hand at the scope that was asked about. Zero when the "
            + "material has no stock there at all.", example = "3501")
    private Double currentStock;

    @Schema(description = "The reorder level that was applied. At storage-location scope this is "
            + "the location's override when it has one, and the material's own level otherwise.",
            example = "11000")
    private Double reorderLevel;

    @Schema(description = "How far below the reorder level the stock is, never negative. Zero for "
            + "a material sitting exactly on its level.", example = "7499")
    private Double shortfall;

    @Schema(description = "The minimum order quantity in force at that scope, as the smallest "
            + "quantity a purchase can be raised for. Null when none is recorded.", example = "10000")
    private Double moq;

    @Schema(description = "Project the stock was counted within, null when the whole organization "
            + "was counted.", example = "5")
    private Long projectId;

    @Schema(description = "Storage location the stock was counted at, null unless one was asked "
            + "about.", example = "2")
    private Long storageLocationId;
}
