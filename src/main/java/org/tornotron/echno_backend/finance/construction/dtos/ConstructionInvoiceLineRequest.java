package org.tornotron.echno_backend.finance.construction.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * Line payload shared by the create and update requests. Amounts (subtotal, tax,
 * discount, total) are computed server-side from quantity, unit price and the
 * percentage rates; the client only supplies the inputs.
 */
@Schema(description = "A single invoice line. The client supplies quantity, unit price and the "
        + "percentage rates; the server computes the line and invoice totals.")
public record ConstructionInvoiceLineRequest(
        @Schema(description = "Description of the billed item or work.", example = "Ready-mix concrete M25")
        @NotBlank @Size(max = 500) String description,

        @Schema(description = "Quantity billed.", example = "12.5")
        @NotNull @DecimalMin(value = "0.0001") BigDecimal quantity,

        @Schema(description = "Unit of measure for the quantity.", example = "cubic metre")
        @NotBlank @Size(max = 50) String unit,

        @Schema(description = "Price per unit before tax and discount.", example = "5400.00")
        @NotNull @DecimalMin(value = "0.0") BigDecimal unitPrice,

        @Schema(description = "Tax rate applied to the line, as a percentage from 0 to 100.", example = "18.0")
        @DecimalMin(value = "0.0") @DecimalMax(value = "100.0") BigDecimal taxRate,

        @Schema(description = "Discount rate applied to the line, as a percentage from 0 to 100.", example = "5.0")
        @DecimalMin(value = "0.0") @DecimalMax(value = "100.0") BigDecimal discountRate,

        @Schema(description = "Inventory item this line draws from, if applicable.", example = "88")
        Long inventoryItemId,

        @Schema(description = "Asset this line relates to, if applicable.", example = "14")
        Long assetId,

        @Schema(description = "Task this line is attributed to, if applicable.", example = "512")
        Long taskId
) {}
