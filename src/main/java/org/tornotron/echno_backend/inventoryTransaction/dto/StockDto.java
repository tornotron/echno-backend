package org.tornotron.echno_backend.inventoryTransaction.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class StockDto {
    private String materialName;
    private Long materialId;
    private String unit;
    private Double stock;
    private BigDecimal stockValue;
}
