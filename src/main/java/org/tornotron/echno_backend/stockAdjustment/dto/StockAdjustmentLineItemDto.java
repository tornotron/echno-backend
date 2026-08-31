package org.tornotron.echno_backend.stockAdjustment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "A single material line on a stock adjustment, comparing the system quantity to the physically counted quantity.")
@Data
public class StockAdjustmentLineItemDto {
    @Schema(description = "Id of the line item.", example = "56")
    private Long id;

    @Schema(description = "Id of the material being adjusted.", example = "21")
    private Long materialId;

    @Schema(description = "Name of the material.", example = "OPC 53 Grade Cement")
    private String materialName;

    @Schema(description = "Free-text description of the line item.", example = "Damaged bags found during count")
    private String description;

    @Schema(description = "Quantity on record before the adjustment, read from the balance when the "
            + "document was last written. An approval is refused if the balance has moved away from it, "
            + "so on a posted line this is also the opening figure the ledger entry carries.",
            example = "480.0")
    private Double systemQuantity;

    @Schema(description = "Quantity found during the physical count.", example = "460.0")
    private Double physicalQuantity;

    @Schema(description = "Adjustment quantity (physical minus system).", example = "-20.0")
    private Double adjustmentQuantity;

    @Schema(description = "Unit of measure for the quantities.", example = "bags")
    private String unit;

    @Schema(description = "Value per unit.", example = "380.00")
    private BigDecimal unitValue;

    @Schema(description = "Total monetary value of this line's adjustment.", example = "-7600.00")
    private BigDecimal totalAdjustmentValue;

    @Schema(description = "Reason category for this line's variance.", example = "DAMAGE")
    private String reason;

    @Schema(description = "Additional detail on the reason.", example = "Bags damaged by moisture ingress near the store roof")
    private String reasonDetails;

    @Schema(description = "Id of the storage location this line item was counted at.", example = "7")
    private Long locationId;

    @Schema(description = "Name of the storage location.", example = "Main Site Store")
    private String locationName;

    @Schema(description = "Bin or rack location within the storage location.", example = "Rack B-3")
    private String binLocation;

    @Schema(description = "Additional notes for this line item.", example = "Photos attached to the count sheet")
    private String notes;
}
