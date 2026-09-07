package org.tornotron.echno_backend.purchaseOrderItem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A purchase order line as the item endpoints serve it. A field without
 * {@code nullable = true} is one the schema, the mapper or the service behind it establishes
 * as always present; see {@code ReviewedResponseSchemas} for which is which.
 */
@Schema(description = "A purchase order line item, returned standalone by the item endpoints.")
@Data
public class PurchaseOrderItemResponseDto {

    @Schema(description = "Purchase order item id.", example = "512")
    private Long id;

    @Schema(description = "Id of the purchase order this item belongs to.", example = "204")
    private Long purchaseOrderId;

    @Schema(description = "Number of the parent purchase order.", example = "PO-2026-0042")
    private String poNumber;

    @Schema(description = "Id of the material being ordered.", example = "44")
    private Long materialId;

    @Schema(description = "Name of the material.", example = "TMT Bar Fe 500D, 12mm")
    private String materialName;

    @Schema(description = "Id of the source indent item this line was converted from. Null on a "
            + "line entered directly rather than from an indent.", example = "31", nullable = true)
    private Long indentItemId;

    @Schema(description = "Quantity ordered.", example = "500")
    private Integer orderedQuantity;

    @Schema(description = "Quantity received against this line so far.", example = "0")
    private Integer receivedQuantity;

    @Schema(description = "Unit price in INR. Null where no price was agreed when the line was "
            + "raised, which is not the same as a price of zero.", example = "62.50",
            nullable = true)
    private BigDecimal unitPrice;

    @Schema(description = "Total price for this line in INR. The server computes it on every "
            + "write, treating a missing unit price as zero, so a zero means no price was agreed "
            + "rather than a line that costs nothing. The column still permits null, so a row "
            + "written outside the application can carry none.", example = "31250.00",
            nullable = true)
    private BigDecimal totalPrice;

    @Schema(description = "Free-text remarks on this line item. Null where none were written.",
            example = "IS 1786 grade, mill test certificate required", nullable = true)
    private String remarks;
}
