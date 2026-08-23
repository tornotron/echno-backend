package org.tornotron.echno_backend.finance.construction.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Payload to fully replace an editable construction invoice. Status and payment "
        + "status are set directly, and money totals are always recomputed from the supplied lines.")
public record UpdateConstructionInvoiceRequest(
        @Schema(description = "Kind of invoice.", example = "VENDOR_BILL")
        @NotNull ConstructionInvoiceType type,

        @Schema(description = "Lifecycle status to set on the invoice.", example = "APPROVED")
        @NotNull ConstructionInvoiceStatus status,

        @Schema(description = "Settlement status to set on the invoice.", example = "PARTIALLY_PAID")
        @NotNull ConstructionPaymentStatus paymentStatus,

        @Schema(description = "Project the invoice is billed against.", example = "42")
        @NotNull Long projectId,

        @Schema(description = "Vendor being invoiced, if any.", example = "17")
        Long vendorId,

        @Schema(description = "Matched purchase order, if any.", example = "108")
        Long purchaseOrderId,

        @Schema(description = "Matched goods receipt, if any.", example = "231")
        Long goodsReceiptId,

        @Schema(description = "Date the invoice was issued.", example = "2026-08-01")
        @NotNull LocalDate issueDate,

        @Schema(description = "Date payment is due.", example = "2026-08-31")
        @NotNull LocalDate dueDate,

        @Schema(description = "Date the invoice was paid, once settled.", example = "2026-08-28")
        LocalDate paymentDate,

        @Schema(description = "Free-text payment terms.", example = "Net 30")
        @Size(max = 100) String paymentTerms,

        @Schema(description = "Settlement method.", example = "BANK_TRANSFER")
        @Size(max = 50) String paymentMethod,

        @Schema(description = "Vendor GST registration number.", example = "29ABCDE1234F1Z5")
        @Size(max = 30) String gstNumber,

        @Schema(description = "Tax treatment applied to the invoice.", example = "CGST_SGST")
        @Size(max = 20) String taxType,

        @Schema(description = "Internal notes about the invoice.", example = "Second progress claim for tower B")
        @Size(max = 1000) String notes,

        @Schema(description = "Terms and conditions printed on the invoice.")
        @Size(max = 2000) String termsAndConditions,

        @Schema(description = "Invoice line items. At least one line is required.")
        @NotNull @Size(min = 1) @Valid List<ConstructionInvoiceLineRequest> lines
) {}
