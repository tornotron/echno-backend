package org.tornotron.echno_backend.inventoryTransaction.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MaterialLocationStockDto {
    private Long materialId;
    private String materialName;
    private List<LocationStockDto> locationStock;
    private Double totalStock;
    private BigDecimal totalStockValue;
}
