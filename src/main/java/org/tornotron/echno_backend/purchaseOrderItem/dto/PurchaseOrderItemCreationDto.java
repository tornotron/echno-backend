package org.tornotron.echno_backend.purchaseOrderItem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "Payload to add a line item to a purchase order.")
@Data
public class PurchaseOrderItemCreationDto {

    @Schema(description = "Id of the purchase order this item belongs to.", example = "204")
    private Long purchaseOrderId;

    @Schema(description = "Id of the material being ordered.", example = "44")
    @NotNull(message = "Material ID is required")
    private Long materialId;

    @Schema(description = "Id of the source indent item this line was converted from, if any.", example = "31")
    private Long indentItemId;

    @Schema(description = "Quantity ordered.", example = "500")
    @NotNull(message = "Ordered quantity is required")
    @Min(value = 1, message = "Ordered quantity must be at least 1")
    private Integer orderedQuantity;

    @Schema(description = "Unit price in INR.", example = "62.50")
    private BigDecimal unitPrice;

    @Schema(description = "Total price for this line in INR.", example = "31250.00")
    private BigDecimal totalPrice;

    @Schema(description = "Free-text remarks on this line item.", example = "IS 1786 grade, mill test certificate required")
    private String remarks;
}
