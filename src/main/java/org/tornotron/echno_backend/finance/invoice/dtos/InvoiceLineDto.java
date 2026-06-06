package org.tornotron.echno_backend.finance.invoice.dtos;

import java.math.BigDecimal;
import java.util.UUID;

public record InvoiceLineDto(
        UUID id, String description, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal lineSubtotal, BigDecimal taxRate, BigDecimal taxAmount, BigDecimal lineTotal,
        UUID revenueAccountId, String revenueAccountCode
) {}