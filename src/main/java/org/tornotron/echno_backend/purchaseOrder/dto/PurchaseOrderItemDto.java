package org.tornotron.echno_backend.purchaseOrder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "A purchase order line item as embedded in a purchase order response.")
@Data
public class PurchaseOrderItemDto {

    @Schema(description = "Purchase order item id.", example = "512")
    private Long id;

    @Schema(description = "Id of the material being ordered.", example = "44")
    @NotNull(message = "material ID is required")
    private Long materialId;

    @Schema(description = "Name of the material.", example = "TMT Bar Fe 500D, 12mm")
    private String materialName;

    @Schema(description = "Id of the source indent item this line was converted from, if any.", example = "31")
    private Long indentItemId;

    @Schema(description = "Quantity ordered.", example = "500")
    @NotNull(message = "ordered quantity is required")
    @Min(value = 1, message = "ordered quantity must be at least 1")
    private Integer orderedQuantity;

    @Schema(description = "Quantity received against this line so far.", example = "0")
    private Integer receivedQuantity;

    @Schema(description = "Unit price in INR.", example = "62.50")
    private BigDecimal unitPrice;

    @Schema(description = "Total price for this line in INR.", example = "31250.00")
    private BigDecimal totalPrice;

    @Schema(description = "Free-text remarks on this line item.", example = "IS 1786 grade, mill test certificate required")
    private String remarks;
}
