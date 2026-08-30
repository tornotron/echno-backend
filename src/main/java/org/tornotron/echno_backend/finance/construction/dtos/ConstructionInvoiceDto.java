package org.tornotron.echno_backend.finance.construction.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "A construction invoice with its computed totals, lifecycle status and line items.")
public record ConstructionInvoiceDto(
        @Schema(description = "Unique invoice id.", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        UUID id,

        @Schema(description = "Human-readable invoice number assigned by the system.", example = "CINV-2026-0042")
        String invoiceNumber,

        @Schema(description = "Kind of invoice.", example = "VENDOR_BILL")
        ConstructionInvoiceType type,

        @Schema(description = "Lifecycle status of the invoice.", example = "APPROVED")
        ConstructionInvoiceStatus status,

        @Schema(description = "Settlement status derived from paid and outstanding amounts.", example = "PARTIALLY_PAID")
        ConstructionPaymentStatus paymentStatus,

        @Schema(description = "Project the invoice is billed against.", example = "42")
        Long projectId,

        @Schema(description = "Vendor being invoiced, if any.", example = "17")
        Long vendorId,

        @Schema(description = "Matched purchase order, if any.", example = "108")
        Long purchaseOrderId,

        @Schema(description = "Matched goods receipt, if any.", example = "231")
        Long goodsReceiptId,

        @Schema(description = "Date the invoice was issued.", example = "2026-08-01")
        LocalDate issueDate,

        @Schema(description = "Date payment is due.", example = "2026-08-31")
        LocalDate dueDate,

        @Schema(description = "Date the invoice was fully paid, once settled.", example = "2026-08-28")
        LocalDate paymentDate,

        @Schema(description = "Sum of line amounts before tax and discount.", example = "67500.00")
        BigDecimal subtotal,

        @Schema(description = "Total tax across all lines.", example = "12150.00")
        BigDecimal taxAmount,

        @Schema(description = "Total discount across all lines.", example = "3375.00")
        BigDecimal discountAmount,

        @Schema(description = "Invoice grand total after tax and discount.", example = "76275.00")
        BigDecimal totalAmount,

        @Schema(description = "Amount paid so far.", example = "40000.00")
        BigDecimal paidAmount,

        @Schema(description = "Outstanding balance still due.", example = "36275.00")
        BigDecimal balanceAmount,

        @Schema(description = "Free-text payment terms.", example = "Net 30")
        String paymentTerms,

        @Schema(description = "Settlement method.", example = "BANK_TRANSFER")
        String paymentMethod,

        @Schema(description = "Vendor GST registration number.", example = "29ABCDE1234F1Z5")
        String gstNumber,

        @Schema(description = "Tax treatment applied to the invoice.", example = "CGST_SGST")
        String taxType,

        @Schema(description = "Internal notes.", example = "Second progress claim for tower B")
        String notes,

        @Schema(description = "Terms and conditions printed on the invoice.")
        String termsAndConditions,

        @Schema(description = "User id that submitted the invoice for approval.", example = "5")
        Long submittedBy,

        @Schema(description = "Name of the user that submitted the invoice. Reads \"User #<id>\" when the "
                + "account has since been deleted; null only when the invoice was never submitted.",
                example = "Anand Rajashekar")
        String submittedByName,

        @Schema(description = "Timestamp the invoice was submitted.", example = "2026-08-02T09:15:00Z")
        Instant submittedAt,

        @Schema(description = "User id that approved the invoice.", example = "2")
        Long approvedBy,

        @Schema(description = "Name of the user that approved the invoice. Reads \"User #<id>\" when the "
                + "account has since been deleted; null only when the invoice was never approved.",
                example = "Aneesh Johny")
        String approvedByName,

        @Schema(description = "Timestamp the invoice was approved.", example = "2026-08-03T11:40:00Z")
        Instant approvedAt,

        @Schema(description = "User id that recorded the most recent payment.", example = "5")
        Long paymentRecordedBy,

        @Schema(description = "Name of the user that recorded the most recent payment. Reads "
                + "\"User #<id>\" when the account has since been deleted; null only when no payment has "
                + "been recorded.",
                example = "Anand Rajashekar")
        String paymentRecordedByName,

        @Schema(description = "Ledger journal entry posted when the invoice was approved.",
                example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10")
        UUID journalEntryId,

        @Schema(description = "Reversal journal entry, present when the invoice was cancelled after posting.")
        UUID reversalJournalEntryId,

        @Schema(description = "AR invoice raised for this invoice on approval. Present on a sales or "
                + "service invoice whose project has a client; null otherwise.",
                example = "1c9d4b7a-0f2e-4a63-8b11-5d7c2e9f4a88")
        UUID arInvoiceId,

        @Schema(description = "Invoice line items.")
        List<ConstructionInvoiceLineDto> lines
) {}
