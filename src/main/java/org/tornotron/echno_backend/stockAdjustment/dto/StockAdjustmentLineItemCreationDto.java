package org.tornotron.echno_backend.stockAdjustment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "A single material line on a stock adjustment, comparing the system quantity to the physically counted quantity.")
@Data
public class StockAdjustmentLineItemCreationDto {
    @Schema(description = "Id of the material being adjusted.", example = "21")
    private Long materialId;

    @Schema(description = "Free-text description of the line item.", example = "OPC 53 Grade Cement, damaged bags found during count")
    private String description;

    @Schema(description = "The quantity on record before the adjustment. Stamped from the balance this "
            + "line is raised against, so any value sent here is ignored: it is the opening figure the "
            + "approval is checked against and therefore the server's to state. A value sent for a line "
            + "naming no material, or on a document naming no project, is kept, because there is no "
            + "balance to read; such a line cannot be approved either way.", example = "480.0")
    private Double systemQuantity;

    @Schema(description = "Quantity found during the physical count.", example = "460.0")
    private Double physicalQuantity;

    @Schema(description = "Adjustment quantity (physical minus system). Recomputed from the stamped "
            + "system quantity whenever a physical count is given, so the line's three figures are one "
            + "piece of arithmetic. Sent on its own, with no physical count, it is the signed movement "
            + "the line posts and is kept as sent.", example = "-20.0")
    private Double adjustmentQuantity;

    @Schema(description = "Unit of measure for the quantities.", example = "bags")
    private String unit;

    @Schema(description = "Value per unit.", example = "380.00")
    private BigDecimal unitValue;

    @Schema(description = "Total monetary value of this line's adjustment. Computed as the stamped "
            + "adjustment quantity times the unit value, so any value sent here is ignored whenever "
            + "both of those are known. A line carrying no unit value keeps what was sent, because "
            + "there is nothing to multiply by; approving such a line writes its value from the "
            + "running average cost the ledger entry was posted at.", example = "-7600.00")
    private BigDecimal totalAdjustmentValue;

    @Schema(description = "Reason category for this line's variance.", example = "DAMAGE")
    private String reason;

    @Schema(description = "Additional detail on the reason.", example = "Bags damaged by moisture ingress near the store roof")
    private String reasonDetails;

    @Schema(description = "Id of the storage location this line item was counted at.", example = "7")
    private Long locationId;

    @Schema(description = "Bin or rack location within the storage location.", example = "Rack B-3")
    private String binLocation;

    @Schema(description = "Additional notes for this line item.", example = "Photos attached to the count sheet")
    private String notes;
}
