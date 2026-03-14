package org.tornotron.echno_backend.purchaseOrderItem.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PurchaseOrderItemUpdateDto {

    @NotNull(message = "Item ID is required")
    private Long id;

    private Integer orderedQuantity;

    private BigDecimal unitPrice;

    private String remarks;
}
