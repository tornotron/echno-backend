package org.tornotron.echno_backend.inventoryTransaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Schema(description = "Current stock of one material held at a single storage location, with its on-hand "
        + "quantity and value.")
public class LocationStockDto {
    @Schema(description = "Storage location id.", example = "7")
    private Long storageLocationId;

    @Schema(description = "Storage location name.", example = "Site A main store")
    private String storageLocationName;

    @Schema(description = "Project the stock at this location belongs to.", example = "42")
    private Long projectId;

    @Schema(description = "Project name.", example = "Tower B fit-out")
    private String projectName;

    @Schema(description = "Quantity on hand at this location.", example = "60.0")
    private Double stock;

    @Schema(description = "Value of the on-hand quantity at this location.", example = "23700.00")
    private BigDecimal stockValue;
}
