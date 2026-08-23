package org.tornotron.echno_backend.inventoryTransaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Schema(description = "Current stock of one material at a storage location, with its on-hand quantity and value.")
public class StockDto {
    @Schema(description = "Material name.", example = "Portland Cement 53 grade")
    private String materialName;

    @Schema(description = "Material id.", example = "310")
    private Long materialId;

    @Schema(description = "Unit of measure the quantity is expressed in.", example = "bag")
    private String unit;

    @Schema(description = "Quantity on hand.", example = "105.0")
    private Double stock;

    @Schema(description = "Value of the on-hand quantity.", example = "41475.00")
    private BigDecimal stockValue;
}
