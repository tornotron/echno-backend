package org.tornotron.echno_backend.purchaseOrderItem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "Payload to update a purchase order line item's quantity, price or remarks.")
@Data
public class PurchaseOrderItemUpdateDto {

    @Schema(description = "Id of the item to update.", example = "512")
    @NotNull(message = "Item ID is required")
    private Long id;

    @Schema(description = "Updated ordered quantity.", example = "450")
    private Integer orderedQuantity;

    @Schema(description = "Updated unit price in INR.", example = "64.00")
    private BigDecimal unitPrice;

    @Schema(description = "Updated remarks.", example = "Vendor revised rate for February delivery")
    private String remarks;
}
