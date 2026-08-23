package org.tornotron.echno_backend.inventoryTransaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "Current stock of a single material, broken down by the storage locations that hold "
        + "it with the material totals.")
public class MaterialLocationStockDto {
    @Schema(description = "Material id.", example = "310")
    private Long materialId;

    @Schema(description = "Material name.", example = "Portland Cement 53 grade")
    private String materialName;

    @Schema(description = "Per-location stock lines for this material.")
    private List<LocationStockDto> locationStock;

    @Schema(description = "Total quantity on hand across all locations.", example = "105.0")
    private Double totalStock;

    @Schema(description = "Total value of stock on hand across all locations.", example = "41475.00")
    private BigDecimal totalStockValue;
}
