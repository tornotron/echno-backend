package org.tornotron.echno_backend.finance.construction.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * Full replacement of an editable construction invoice. Status and payment status
 * are set directly (no ledger posting in this increment); money totals are always
 * recomputed from the supplied lines.
 */
public record UpdateConstructionInvoiceRequest(
        @NotNull ConstructionInvoiceType type,
        @NotNull ConstructionInvoiceStatus status,
        @NotNull ConstructionPaymentStatus paymentStatus,
        @NotNull Long projectId,
        Long vendorId,
        Long purchaseOrderId,
        Long goodsReceiptId,
        @NotNull LocalDate issueDate,
        @NotNull LocalDate dueDate,
        LocalDate paymentDate,
        @Size(max = 100) String paymentTerms,
        @Size(max = 50) String paymentMethod,
        @Size(max = 30) String gstNumber,
        @Size(max = 20) String taxType,
        @Size(max = 1000) String notes,
        @Size(max = 2000) String termsAndConditions,
        @NotNull @Size(min = 1) @Valid List<ConstructionInvoiceLineRequest> lines
) {}
