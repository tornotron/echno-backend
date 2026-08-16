package org.tornotron.echno_backend.stockAdjustment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class StockAdjustmentCreationDto {

    private String adjustmentNumber;
    private String type;
    private String status;
    private Long locationId;
    private Long projectId;
    private LocalDate adjustmentDate;
    private LocalDate effectiveDate;

    @NotBlank
    private String justification;

    private String primaryReason;
    private BigDecimal totalAdjustmentValue;
    private LocalDate physicalCountDate;
    private Long physicalCountBy;
    private String countMethod;
    private Long submittedBy;
    private Double totalVarianceQuantity;
    private List<StockAdjustmentLineItemCreationDto> lineItems;
}
