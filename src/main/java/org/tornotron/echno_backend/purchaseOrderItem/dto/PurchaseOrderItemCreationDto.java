package org.tornotron.echno_backend.purchaseOrderItem.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PurchaseOrderItemCreationDto {

    @NotNull(message = "Purchase Order ID is required")
    private Long purchaseOrderId;

    @NotNull(message = "Material ID is required")
    private Long materialId;

    private Long indentItemId;

    @NotNull(message = "Ordered quantity is required")
    @Min(value = 1, message = "Ordered quantity must be at least 1")
    private Integer orderedQuantity;

    private BigDecimal unitPrice;

    private BigDecimal totalPrice;

    private String remarks;
}
