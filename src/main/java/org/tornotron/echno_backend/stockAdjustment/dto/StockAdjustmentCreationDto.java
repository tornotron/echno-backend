package org.tornotron.echno_backend.stockAdjustment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Payload to create or update a stock adjustment document correcting on-hand quantities of materials against a physical count.")
@Data
public class StockAdjustmentCreationDto {

    @Schema(description = "Unique adjustment document number.", example = "SA-2026-0014")
    private String adjustmentNumber;

    @Schema(description = "Type of adjustment.", example = "PHYSICAL_COUNT")
    private String type;

    /**
     * The state the document is in. Optional, and {@code draft} is the only value accepted:
     * the posted state is reached by approving the document, which is what checks who is
     * approving it and writes the ledger entries. See {@code StockAdjustmentService.create}.
     */
    @Schema(description = "State of the adjustment document. Optional, and draft is the only "
            + "value accepted, so leaving it out is the same as sending draft. A document "
            + "reaches its posted state through POST /{id}/approve, which is what refuses an "
            + "approval by whoever raised it and writes the stock-ledger entries; a document "
            + "created or edited into that state would carry neither, and would read as approved "
            + "with nobody on record as having approved it and no movement behind it.",
            example = "draft", allowableValues = {"draft"})
    private String status;

    @Schema(description = "Id of the storage location the adjustment applies to.", example = "7")
    private Long locationId;

    @Schema(description = "Id of the project the adjustment applies to.", example = "4")
    private Long projectId;

    @Schema(description = "Date the adjustment document was raised.", example = "2026-01-15")
    private LocalDate adjustmentDate;

    @Schema(description = "Date the adjustment takes effect on the stock ledger.", example = "2026-01-15")
    private LocalDate effectiveDate;

    @Schema(description = "Justification for the adjustment.", example = "Quarterly physical count found a shortfall in River Sand at Main Site Store")
    @NotBlank
    private String justification;

    @Schema(description = "Primary reason category for the adjustment.", example = "PHYSICAL_COUNT_VARIANCE")
    private String primaryReason;

    @Schema(description = "Total monetary value of the adjustment across all line items. Summed from "
            + "the line items, so any value sent here is ignored: it is arithmetic over figures the "
            + "server states and a header that disagreed with the sum of its own lines would be "
            + "reporting neither. Approving restates it from what was posted.", example = "18500.00")
    private BigDecimal totalAdjustmentValue;

    @Schema(description = "Date the physical count was performed.", example = "2026-01-14")
    private LocalDate physicalCountDate;

    @Schema(description = "Id of the employee who performed the physical count.", example = "18")
    private Long physicalCountBy;

    @Schema(description = "Method used to perform the physical count.", example = "FULL_COUNT")
    private String countMethod;

    @Schema(description = "Read-only. The user who raised the document is recorded from the "
            + "authenticated session, because approval is checked against it. Any value sent here "
            + "is ignored.",
            accessMode = Schema.AccessMode.READ_ONLY, example = "18")
    private Long submittedBy;

    @Schema(description = "Total variance quantity across all line items. Summed from the line "
            + "items, so any value sent here is ignored: each line's variance is stamped from the "
            + "balance it was raised against, so a header total computed by the client is a sum of "
            + "figures the server has since replaced. Approving restates it from what was posted.",
            example = "-120.0")
    private Double totalVarianceQuantity;

    @Schema(description = "Line items, one per material, listing the system quantity, the counted quantity, and the variance.")
    private List<StockAdjustmentLineItemCreationDto> lineItems;
}
