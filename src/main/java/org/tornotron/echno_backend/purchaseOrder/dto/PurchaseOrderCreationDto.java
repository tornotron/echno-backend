package org.tornotron.echno_backend.purchaseOrder.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    @NotBlank(message = "created by username is required")
    @Size(min = 1, max = 50, message = "created by must be between 1 and 50 characters")
    private String createdBy;

    private LocalDateTime expectedDeliveryDate;

    private String remarks;

    @NotEmpty(message = "items list cannot be empty")
    @Valid
    private List<PurchaseOrderItemDto> items;

    private BigDecimal totalAmount;
}
