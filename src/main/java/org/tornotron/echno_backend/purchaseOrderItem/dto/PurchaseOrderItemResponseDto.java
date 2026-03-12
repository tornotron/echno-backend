package org.tornotron.echno_backend.purchaseOrderItem.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PurchaseOrderItemResponseDto {

    private Long id;
    private Long purchaseOrderId;
    private String poNumber;
    private Long materialId;
    private String materialName;
    private Long indentItemId;
    private Integer orderedQuantity;
    private Integer receivedQuantity;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    private String remarks;
}
