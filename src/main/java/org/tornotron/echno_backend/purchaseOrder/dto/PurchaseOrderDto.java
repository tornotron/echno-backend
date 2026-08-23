package org.tornotron.echno_backend.purchaseOrder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.user.dto.UserDto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    @Schema(description = "Id of the indent this order was converted from, if any.", example = "7")
    private Long indentId;

    @Schema(description = "Number of the source indent, if any.", example = "IND-2026-0015")
    private String indentNumber;

    @Schema(description = "Id of the project the materials are for.", example = "3")
    private Long projectId;

    @Schema(description = "Name of the project.", example = "Asset Homes Perumbavoor Phase 2")
    private String projectName;

    @Schema(description = "Current lifecycle status of the purchase order.", example = "SENT_TO_VENDOR")
    private PurchaseOrderStatus status;

    @Schema(description = "Timestamp the purchase order was created.", example = "2026-01-15T10:30:00")
    private LocalDateTime createdAt;

    @Schema(description = "Employee who raised the purchase order.")
    private EmployeeDto createdBy;

    @Schema(description = "Date the vendor is expected to deliver by.", example = "2026-02-10T00:00:00")
    private LocalDateTime expectedDeliveryDate;

    @Schema(description = "Free-text remarks on the order.", example = "Deliver to Perumbavoor site, second gate")
    private String remarks;

    @Schema(description = "Line items on the order.")
    private List<PurchaseOrderItemDto> items;

    @Schema(description = "Total value of the purchase order in INR.", example = "485000.00")
    private BigDecimal totalAmount;
}
