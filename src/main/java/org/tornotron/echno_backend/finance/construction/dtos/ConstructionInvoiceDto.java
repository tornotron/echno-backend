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

        @Schema(description = "Vendor being invoiced, if any. Null on an invoice raised against no vendor, such "
                + "as a sales or service invoice.", example = "17", nullable = true)
        Long vendorId,

        @Schema(description = "Matched purchase order, if any. Null where none is matched.",
                example = "108", nullable = true)
        Long purchaseOrderId,

        @Schema(description = "Matched goods receipt, if any. Null where none is matched.",
                example = "231", nullable = true)
        Long goodsReceiptId,

        @Schema(description = "Date the invoice was issued.", example = "2026-08-01")
        LocalDate issueDate,

        @Schema(description = "Date payment is due.", example = "2026-08-31")
        LocalDate dueDate,

        @Schema(description = "Date the invoice was fully paid, once settled. Null until the invoice is settled "
                + "in full.", example = "2026-08-28", nullable = true)
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

        @Schema(description = "Free-text payment terms. Null where none were recorded.",
                example = "Net 30", nullable = true)
        String paymentTerms,

        @Schema(description = "Settlement method. Null where none was recorded.",
                example = "BANK_TRANSFER", nullable = true)
        String paymentMethod,

        @Schema(description = "Vendor GST registration number. Null where none was recorded.",
                example = "29ABCDE1234F1Z5", nullable = true)
        String gstNumber,

        @Schema(description = "Tax treatment applied to the invoice. Null where none was recorded.",
                example = "CGST_SGST", nullable = true)
        String taxType,

        @Schema(description = "Internal notes. Null where none were recorded.",
                example = "Second progress claim for tower B", nullable = true)
        String notes,

        @Schema(description = "Terms and conditions printed on the invoice. Null where none were recorded.",
                nullable = true)
        String termsAndConditions,

        @Schema(description = "User id that submitted the invoice for approval. Null until the invoice is "
                + "submitted for approval.", example = "5", nullable = true)
        Long submittedBy,

        @Schema(description = "Name of the user that submitted the invoice, or their email where the "
                + "account carries no name. Reads \"User #<id>\" when the account has since been "
                + "deleted; null only when the invoice was never submitted.",
                example = "Anand Rajashekar", nullable = true)
        String submittedByName,

        @Schema(description = "Timestamp the invoice was submitted. Null until the invoice is submitted for "
                + "approval.", example = "2026-08-02T09:15:00Z", nullable = true)
        Instant submittedAt,

        @Schema(description = "User id that approved the invoice. Null until the invoice is approved.",
                example = "2", nullable = true)
        Long approvedBy,

        @Schema(description = "Name of the user that approved the invoice, or their email where the "
                + "account carries no name. Reads \"User #<id>\" when the account has since been "
                + "deleted; null only when the invoice was never approved.",
                example = "Aneesh Johny", nullable = true)
        String approvedByName,

        @Schema(description = "Timestamp the invoice was approved. Null until the invoice is approved.",
                example = "2026-08-03T11:40:00Z", nullable = true)
        Instant approvedAt,

        @Schema(description = "User id that recorded the most recent payment. Null until a payment is recorded "
                + "against the invoice.", example = "5", nullable = true)
        Long paymentRecordedBy,

        @Schema(description = "Name of the user that recorded the most recent payment, or their email "
                + "where the account carries no name. Reads \"User #<id>\" when the account has since "
                + "been deleted; null only when no payment has been recorded.",
                example = "Anand Rajashekar", nullable = true)
        String paymentRecordedByName,

        @Schema(description = "Ledger journal entry posted when the invoice was approved. Null until the "
                + "invoice is approved and posted to the ledger.",
                example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10", nullable = true)
        UUID journalEntryId,

        @Schema(description = "Reversal journal entry, present when the invoice was cancelled after posting. "
                + "Null on an invoice that was never cancelled after posting.", nullable = true)
        UUID reversalJournalEntryId,

        @Schema(description = "AR invoice raised for this invoice on approval. Present on a sales or "
                + "service invoice whose project has a client; null otherwise.",
                example = "1c9d4b7a-0f2e-4a63-8b11-5d7c2e9f4a88", nullable = true)
        UUID arInvoiceId,

        @Schema(description = "Invoice line items.")
        List<ConstructionInvoiceLineDto> lines
) {}
