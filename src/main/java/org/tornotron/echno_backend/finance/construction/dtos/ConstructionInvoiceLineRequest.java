package org.tornotron.echno_backend.finance.construction.dtos;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * Line payload shared by the create and update requests. Amounts (subtotal, tax,
 * discount, total) are computed server-side from quantity, unit price and the
 * percentage rates; the client only supplies the inputs.
 */
public record ConstructionInvoiceLineRequest(
        @NotBlank @Size(max = 500) String description,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal quantity,
        @NotBlank @Size(max = 50) String unit,
        @NotNull @DecimalMin(value = "0.0") BigDecimal unitPrice,
        @DecimalMin(value = "0.0") @DecimalMax(value = "100.0") BigDecimal taxRate,
        @DecimalMin(value = "0.0") @DecimalMax(value = "100.0") BigDecimal discountRate,
        Long inventoryItemId,
        Long assetId,
        Long taskId
) {}
