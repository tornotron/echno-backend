package org.tornotron.echno_backend.purchaseOrder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PurchaseOrderCreationDto {

    @NotBlank(message = "PO number is required")
    @Size(min = 1, max = 50, message = "PO number must be between 1 and 50 characters")
    private String poNumber;

    @NotNull(message = "vendor ID is required")
    private Long vendorId;

    private Long intendId;

    @NotBlank(message = "status is required")
    private String status;

    @NotNull(message = "project ID is required")
    private Long projectId;

    @NotNull(message = "created by employee id is required")
    private Long createdBy;

    private LocalDateTime expectedDeliveryDate;

    private String remarks;

    private BigDecimal totalAmount;
}
