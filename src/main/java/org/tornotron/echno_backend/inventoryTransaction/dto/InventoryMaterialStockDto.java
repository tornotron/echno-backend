package org.tornotron.echno_backend.inventoryTransaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "Current stock held at a single storage location, broken down by material with the "
        + "location totals.")
public class InventoryMaterialStockDto {
    @Schema(description = "Storage location id.", example = "7")
    private Long storageLocationId;

    @Schema(description = "Storage location name.", example = "Site A main store")
    private String storageLocationName;

    @Schema(description = "Project the storage location belongs to.", example = "42")
    private Long projectId;

    @Schema(description = "Per-material stock lines held at this location.")
    private List<StockDto> materialStock;

    @Schema(description = "Total quantity on hand across all materials at this location.", example = "265.0")
    private Double totalStock;

    @Schema(description = "Total value of stock on hand at this location.", example = "108900.00")
    private BigDecimal totalStockValue;
}
