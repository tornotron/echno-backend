package org.tornotron.echno_backend.purchaseOrder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemCreationDto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Payload to raise a purchase order against a vendor, with its line items. "
        + "The PO number is allocated by the server and returned on the created order; it is not "
        + "part of this payload.")
@Data
public class PurchaseOrderCreationDto {

    @Schema(description = "Id of the vendor the order is raised against.", example = "12")
    @NotNull(message = "vendor ID is required")
    private Long vendorId;

    @Schema(description = "Id of the indent this order was converted from, if any.", example = "7")
    private Long indentId;

    @Schema(description = "Status the order starts in. Optional, and DRAFT is the only value "
            + "accepted, so leaving it out is the same as sending DRAFT. Approval and every later "
            + "state change go through PATCH /purchase-orders/{id}/status, which is what makes "
            + "approving an order a deliberate act rather than a field on the create form.",
            example = "DRAFT", allowableValues = {"DRAFT"})
    private PurchaseOrderStatus status;

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

    @Schema(description = "Total value of the purchase order in INR. Ignored on create: the total "
            + "is the sum of the line totals the server computes.", example = "485000.00")
    private BigDecimal totalAmount;

    @Schema(description = "Line items being ordered.")
    @Valid
    private List<PurchaseOrderItemCreationDto> items;
}
