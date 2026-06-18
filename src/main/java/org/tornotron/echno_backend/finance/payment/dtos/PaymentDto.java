package org.tornotron.echno_backend.finance.payment.dtos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PaymentDto(
        UUID id, String paymentNumber,
        UUID customerId, String customerName,
        LocalDate paymentDate, BigDecimal amount,
        UUID companyBankAccountId, String bankName, String bankAccountNumber,
        String externalReference, UUID journalEntryId, String notes,
        List<AllocationDto> allocations
) {
    public record AllocationDto(
            UUID id, UUID invoiceId, String invoiceNumber, BigDecimal allocatedAmount
    ) {}
}
