package org.tornotron.echno_backend.purchaseOrder.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PurchaseOrderUpdateDto {

    @NotNull(message = "Purchase Order ID is required")
    private Long id;

    private String status;

    private LocalDateTime expectedDeliveryDate;

    private String remarks;

    private BigDecimal totalAmount;
}
