package org.tornotron.echno_backend.purchaseOrder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemCreationDto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Payload to raise a purchase order against a vendor, with its line items.")
@Data
public class PurchaseOrderCreationDto {

    @Schema(description = "Purchase order number, unique per organization.", example = "PO-2026-0042")
    @NotBlank(message = "PO number is required")
    @Size(min = 1, max = 50, message = "PO number must be between 1 and 50 characters")
    private String poNumber;

    @Schema(description = "Id of the vendor the order is raised against.", example = "12")
    @NotNull(message = "vendor ID is required")
    private Long vendorId;

    @Schema(description = "Id of the indent this order was converted from, if any.", example = "7")
    private Long indentId;

    @Schema(description = "Lifecycle status of the purchase order.", example = "DRAFT")
    @NotBlank(message = "status is required")
    private String status;

    @Schema(description = "Id of the project the materials are for.", example = "3")
    @NotNull(message = "project ID is required")
    private Long projectId;

    @Schema(description = "Id of the employee raising the purchase order.", example = "8")
    @NotNull(message = "created by employee id is required")
    private Long createdBy;

    @Schema(description = "Date the vendor is expected to deliver by.", example = "2026-02-10T00:00:00")
    private LocalDateTime expectedDeliveryDate;

    @Schema(description = "Free-text remarks on the order.", example = "Deliver to Perumbavoor site, second gate")
    private String remarks;

    @Schema(description = "Total value of the purchase order in INR.", example = "485000.00")
    private BigDecimal totalAmount;

    @Schema(description = "Line items being ordered.")
    @Valid
    private List<PurchaseOrderItemCreationDto> items;
}
