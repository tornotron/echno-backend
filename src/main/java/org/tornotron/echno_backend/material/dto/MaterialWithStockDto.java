package org.tornotron.echno_backend.material.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class MaterialWithStockDto {

    private Long id;
    private String sku;
    private String materialName;
    private String unit;
    private Double currentStock;
    private BigDecimal stockValue;
}
