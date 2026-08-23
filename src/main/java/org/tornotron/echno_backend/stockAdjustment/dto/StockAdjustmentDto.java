package org.tornotron.echno_backend.stockAdjustment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "A stock adjustment document correcting on-hand quantities of materials against a physical count, with its workflow and audit fields.")
@Data
public class StockAdjustmentDto {
    @Schema(description = "Id of the stock adjustment.", example = "14")
    private Long id;

    @Schema(description = "Unique adjustment document number.", example = "SA-2026-0014")
    private String adjustmentNumber;

    @Schema(description = "Type of adjustment.", example = "PHYSICAL_COUNT")
    private String type;

    @Schema(description = "Status of the adjustment document.", example = "APPROVED")
    private String status;

    @Schema(description = "Id of the storage location the adjustment applies to.", example = "7")
    private Long locationId;

    @Schema(description = "Name of the storage location.", example = "Main Site Store")
    private String locationName;

    @Schema(description = "Id of the project the adjustment applies to.", example = "4")
    private Long projectId;

    @Schema(description = "Name of the project.", example = "Asset Homes - Kochi Phase 2")
    private String projectName;

    @Schema(description = "Id of the owning organization.", example = "1")
    private Long organizationId;

    @Schema(description = "Date the adjustment document was raised.", example = "2026-01-15")
    private LocalDate adjustmentDate;

    @Schema(description = "Date the adjustment takes effect on the stock ledger.", example = "2026-01-15")
    private LocalDate effectiveDate;

    @Schema(description = "Total monetary value of the adjustment across all line items.", example = "18500.00")
    private BigDecimal totalAdjustmentValue;

    @Schema(description = "Primary reason category for the adjustment.", example = "PHYSICAL_COUNT_VARIANCE")
    private String primaryReason;

    @Schema(description = "Justification for the adjustment.", example = "Quarterly physical count found a shortfall in River Sand at Main Site Store")
    private String justification;

    @Schema(description = "Date the physical count was performed.", example = "2026-01-14")
    private LocalDate physicalCountDate;

    @Schema(description = "Id of the employee who performed the physical count.", example = "18")
    private Long physicalCountBy;

    @Schema(description = "Method used to perform the physical count.", example = "FULL_COUNT")
    private String countMethod;

    @Schema(description = "Id of the employee who submitted the adjustment.", example = "18")
    private Long submittedBy;

    @Schema(description = "Date and time the adjustment was submitted.", example = "2026-01-15T10:00:00")
    private LocalDateTime submittedAt;

    @Schema(description = "Id of the employee who approved the adjustment.", example = "3")
    private Long approvedBy;

    @Schema(description = "Date and time the adjustment was approved.", example = "2026-01-16T09:00:00")
    private LocalDateTime approvedAt;

    @Schema(description = "Id of the employee who rejected the adjustment, if it was rejected.", example = "3")
    private Long rejectedBy;

    @Schema(description = "Date and time the adjustment was rejected, if it was rejected.", example = "2026-01-16T09:00:00")
    private LocalDateTime rejectedAt;

    @Schema(description = "Reason given for rejecting the adjustment.", example = "Variance not supported by the count sheet")
    private String rejectionReason;

    @Schema(description = "Id of the employee who processed the adjustment.", example = "3")
    private Long processedBy;

    @Schema(description = "Date and time the adjustment was processed.", example = "2026-01-16T11:00:00")
    private LocalDateTime processedAt;

    @Schema(description = "Total variance quantity across all line items.", example = "-120.0")
    private Double totalVarianceQuantity;

    @Schema(description = "Line items, one per material, listing the system quantity, the counted quantity, and the variance.")
    private List<StockAdjustmentLineItemDto> lineItems;

    @Schema(description = "Date and time the record was created.", example = "2026-01-15T09:45:00")
    private LocalDateTime createdAt;

    @Schema(description = "Date and time the record was last updated.", example = "2026-01-16T11:00:00")
    private LocalDateTime updatedAt;
}
