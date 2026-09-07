package org.tornotron.echno_backend.purchaseOrder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A purchase order as it is served. A field without {@code nullable = true} is one the
 * schema, the mapper or the service behind it establishes as always present; see
 * {@code ReviewedResponseSchemas} for which is which.
 */
@Schema(description = "A purchase order with its vendor, indent link, line items and totals.")
@Data
public class PurchaseOrderDto {

    @Schema(description = "Purchase order id.", example = "204")
    private Long id;

    @Schema(description = "Purchase order number.", example = "PO-2026-0042")
    private String poNumber;

    @Schema(description = "Id of the vendor the order is raised against.", example = "12")
    private Long vendorId;

    @Schema(description = "Name of the vendor.", example = "Sri Balaji Steel Traders")
    private String vendorName;

    @Schema(description = "Id of the indent this order was converted from. Null on an order "
            + "raised directly rather than from an indent.", example = "7", nullable = true)
    private Long indentId;

    @Schema(description = "Number of the source indent. Null whenever indentId is.",
            example = "IND-2026-0015", nullable = true)
    private String indentNumber;

    @Schema(description = "Id of the project the materials are for. The column permits null and "
            + "rows exist without it, although the entity declares the association non-optional.",
            example = "3", nullable = true)
    private Long projectId;

    @Schema(description = "Name of the project. Null whenever projectId is, and also on a project "
            + "that has no name recorded.", example = "Asset Homes Perumbavoor Phase 2",
            nullable = true)
    private String projectName;

    @Schema(description = "Current lifecycle status of the purchase order.", example = "SENT_TO_VENDOR")
    private PurchaseOrderStatus status;

    @Schema(description = "Timestamp the purchase order was created.", example = "2026-01-15T10:30:00")
    private LocalDateTime createdAt;

    @Schema(description = "Employee who raised the purchase order. Null on rows created before "
            + "the author was recorded, and on any row whose author was not resolved.",
            nullable = true)
    private EmployeeDto createdBy;

    @Schema(description = "Date the vendor is expected to deliver by. Null where none was agreed.",
            example = "2026-02-10T00:00:00", nullable = true)
    private LocalDateTime expectedDeliveryDate;

    @Schema(description = "Free-text remarks on the order. Null where none were written.",
            example = "Deliver to Perumbavoor site, second gate", nullable = true)
    private String remarks;

    @Schema(description = "Line items on the order. Null rather than an empty array when the "
            + "order has no lines: the mapper clears an empty list deliberately.", nullable = true)
    private List<PurchaseOrderItemDto> items;

    @Schema(description = "Total value of the purchase order in INR, summed from the line "
            + "totals. The server writes it on every path, seeding zero at creation and treating "
            + "a sum over no lines as zero, so a zero means no priced lines rather than an order "
            + "worth nothing. The column still permits null, so a row written outside the "
            + "application can carry none.", example = "485000.00", nullable = true)
    private BigDecimal totalAmount;
}
