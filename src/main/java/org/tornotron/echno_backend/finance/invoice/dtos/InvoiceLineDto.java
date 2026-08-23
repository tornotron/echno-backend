package org.tornotron.echno_backend.finance.invoice.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "A single line on a customer invoice with its computed subtotal, tax and total.")
public record InvoiceLineDto(
        @Schema(description = "Unique line id.", example = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
        UUID id,

        @Schema(description = "What the line is charging for.", example = "Structural design consultancy")
        String description,

        @Schema(description = "Quantity billed.", example = "10")
        BigDecimal quantity,

        @Schema(description = "Price per unit.", example = "5000.00")
        BigDecimal unitPrice,

        @Schema(description = "Line amount before tax.", example = "50000.00")
        BigDecimal lineSubtotal,

        @Schema(description = "Tax rate applied to the line, as a percentage.", example = "18.0")
        BigDecimal taxRate,

        @Schema(description = "Tax charged on the line.", example = "9000.00")
        BigDecimal taxAmount,

        @Schema(description = "Line total including tax.", example = "59000.00")
        BigDecimal lineTotal,

        @Schema(description = "Revenue account the line is credited to.",
                example = "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e")
        UUID revenueAccountId,

        @Schema(description = "Code of the revenue account.", example = "4000")
        String revenueAccountCode
) {}