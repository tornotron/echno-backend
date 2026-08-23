package org.tornotron.echno_backend.finance.invoice.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "A customer invoice with its computed totals, receivable status and line items.")
public record InvoiceDto(
        @Schema(description = "Unique invoice id.", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        UUID id,

        @Schema(description = "Human-readable invoice number assigned by the system.", example = "INV-2026-0042")
        String invoiceNumber,

        @Schema(description = "Customer the invoice is billed to.", example = "6b1e9c22-9f8a-4a1b-9c0e-1d2f3a4b5c6d")
        UUID customerId,

        @Schema(description = "Customer name captured on the invoice.", example = "Asset Homes Pvt Ltd")
        String customerName,

        @Schema(description = "Date the invoice was issued.", example = "2026-08-01")
        LocalDate invoiceDate,

        @Schema(description = "Date payment is due.", example = "2026-08-31")
        LocalDate dueDate,

        @Schema(description = "Lifecycle status of the invoice.", example = "ISSUED")
        InvoiceStatus status,

        @Schema(description = "Sum of line amounts before tax.", example = "50000.00")
        BigDecimal subtotal,

        @Schema(description = "Total tax across all lines.", example = "9000.00")
        BigDecimal taxTotal,

        @Schema(description = "Invoice grand total after tax.", example = "59000.00")
        BigDecimal total,

        @Schema(description = "Amount received against the invoice so far.", example = "20000.00")
        BigDecimal amountPaid,

        @Schema(description = "Outstanding balance still due.", example = "39000.00")
        BigDecimal balanceDue,

        @Schema(description = "Ledger journal entry posted when the invoice was issued.",
                example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10")
        UUID journalEntryId,

        @Schema(description = "Reversal journal entry, present when the invoice was cancelled after issue.")
        UUID reversalJournalEntryId,

        @Schema(description = "Internal notes.", example = "First milestone billing")
        String notes,

        @Schema(description = "Invoice line items.")
        List<InvoiceLineDto> lines
) {
}
