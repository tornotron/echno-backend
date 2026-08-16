package org.tornotron.echno_backend.stockAdjustment.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockAdjustmentLineItemDto {
    private Long id;
    private Long materialId;
    private String materialName;
    private String description;
    private Double systemQuantity;
    private Double physicalQuantity;
    private Double adjustmentQuantity;
    private String unit;
    private BigDecimal unitValue;
    private BigDecimal totalAdjustmentValue;
    private String reason;
    private String reasonDetails;
    private Long locationId;
    private String locationName;
    private String binLocation;
    private String notes;
}
