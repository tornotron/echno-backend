package org.tornotron.echno_backend.inventoryTransaction.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class LocationStockDto {
    private Long storageLocationId;
    private String storageLocationName;
    private Long projectId;
    private String projectName;
    private Double stock;
    private BigDecimal stockValue;
}
