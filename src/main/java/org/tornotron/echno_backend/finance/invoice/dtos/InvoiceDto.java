package org.tornotron.echno_backend.finance.invoice.dtos;

import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoiceDto(
        UUID id, String invoiceNumber,
        UUID customerId, String customerName,
        LocalDate invoiceDate, LocalDate dueDate,
        InvoiceStatus status,
        BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total,
        BigDecimal amountPaid, BigDecimal balanceDue,
        UUID journalEntryId, UUID reversalJournalEntryId,
        String notes,
        List<InvoiceLineDto> lines
) {
}
