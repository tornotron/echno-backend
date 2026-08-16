package org.tornotron.echno_backend.stockAdjustment.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class StockAdjustmentDto {
    private Long id;
    private String adjustmentNumber;
    private String type;
    private String status;
    private Long locationId;
    private String locationName;
    private Long projectId;
    private String projectName;
    private Long organizationId;
    private LocalDate adjustmentDate;
    private LocalDate effectiveDate;
    private BigDecimal totalAdjustmentValue;
    private String primaryReason;
    private String justification;
    private LocalDate physicalCountDate;
    private Long physicalCountBy;
    private String countMethod;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private Long rejectedBy;
    private LocalDateTime rejectedAt;
    private String rejectionReason;
    private Long processedBy;
    private LocalDateTime processedAt;
    private Double totalVarianceQuantity;
    private List<StockAdjustmentLineItemDto> lineItems;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
