package org.tornotron.echno_backend.finance.construction.dtos;

import java.math.BigDecimal;
import java.util.UUID;

public record ConstructionInvoiceLineDto(
        UUID id,
        String description,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal taxRate,
        BigDecimal taxAmount,
        BigDecimal discountRate,
        BigDecimal discountAmount,
        BigDecimal subtotal,
        BigDecimal total,
        Long inventoryItemId,
        Long assetId,
        Long taskId
) {}
