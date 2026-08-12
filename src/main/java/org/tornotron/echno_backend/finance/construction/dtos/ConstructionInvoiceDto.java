package org.tornotron.echno_backend.finance.construction.dtos;

import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ConstructionInvoiceDto(
        UUID id,
        String invoiceNumber,
        ConstructionInvoiceType type,
        ConstructionInvoiceStatus status,
        ConstructionPaymentStatus paymentStatus,
        Long projectId,
        Long vendorId,
        Long purchaseOrderId,
        Long goodsReceiptId,
        LocalDate issueDate,
        LocalDate dueDate,
        LocalDate paymentDate,
        BigDecimal subtotal,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        BigDecimal balanceAmount,
        String paymentTerms,
        String paymentMethod,
        String gstNumber,
        String taxType,
        String notes,
        String termsAndConditions,
        List<ConstructionInvoiceLineDto> lines
) {}
