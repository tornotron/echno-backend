package org.tornotron.echno_backend.inventoryTransaction.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class InventoryMaterialStockDto {
    private List<StockDto> materialStock;
    private Double totalStock;
    private BigDecimal totalStockValue;
}
