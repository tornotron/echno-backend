package org.tornotron.echno_backend.finance.construction.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;

import java.time.LocalDate;
import java.util.List;

public record CreateConstructionInvoiceRequest(
        @NotNull ConstructionInvoiceType type,
        @NotNull Long projectId,
        Long vendorId,
        Long purchaseOrderId,
        Long goodsReceiptId,
        @NotNull LocalDate issueDate,
        @NotNull LocalDate dueDate,
        @Size(max = 100) String paymentTerms,
        @Size(max = 50) String paymentMethod,
        @Size(max = 30) String gstNumber,
        @Size(max = 20) String taxType,
        @Size(max = 1000) String notes,
        @Size(max = 2000) String termsAndConditions,
        @NotNull @Size(min = 1) @Valid List<ConstructionInvoiceLineRequest> lines
) {}
