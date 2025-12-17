package org.tornotron.echno_backend.purchaseOrder.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PurchaseOrderItemDto {

    private Long id;

    @NotNull(message = "material ID is required")
    private Long materialId;

    private String materialName;

    private Long indentItemId;

    @NotNull(message = "ordered quantity is required")
    @Min(value = 1, message = "ordered quantity must be at least 1")
    private Integer orderedQuantity;

    private Integer receivedQuantity;

    private BigDecimal unitPrice;

    private BigDecimal totalPrice;

    private String remarks;
}
