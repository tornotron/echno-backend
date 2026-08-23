package org.tornotron.echno_backend.finance.construction.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "A single line on a construction invoice with its computed tax, discount and totals.")
public record ConstructionInvoiceLineDto(
        @Schema(description = "Unique line id.", example = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
        UUID id,

        @Schema(description = "What the line is billing for.", example = "OPC 53 grade cement")
        String description,

        @Schema(description = "Quantity billed.", example = "200")
        BigDecimal quantity,

        @Schema(description = "Unit of measure for the quantity.", example = "bag")
        String unit,

        @Schema(description = "Price per unit.", example = "350.00")
        BigDecimal unitPrice,

        @Schema(description = "Tax rate applied to the line, as a percentage.", example = "18.0")
        BigDecimal taxRate,

        @Schema(description = "Tax charged on the line.", example = "12600.00")
        BigDecimal taxAmount,

        @Schema(description = "Discount rate applied to the line, as a percentage.", example = "5.0")
        BigDecimal discountRate,

        @Schema(description = "Discount amount deducted from the line.", example = "3500.00")
        BigDecimal discountAmount,

        @Schema(description = "Line amount before tax and discount.", example = "70000.00")
        BigDecimal subtotal,

        @Schema(description = "Line total after tax and discount.", example = "79100.00")
        BigDecimal total,

        @Schema(description = "Inventory item the line draws from, if any.", example = "512")
        Long inventoryItemId,

        @Schema(description = "Asset the line relates to, if any.", example = "88")
        Long assetId,

        @Schema(description = "Task the line is charged against, if any.", example = "1204")
        Long taskId
) {}
