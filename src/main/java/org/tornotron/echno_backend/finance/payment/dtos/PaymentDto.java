package org.tornotron.echno_backend.finance.payment.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "A payment received from a customer, with the amount allocated across their invoices.")
public record PaymentDto(
        @Schema(description = "Unique payment id.", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        UUID id,

        @Schema(description = "Human-readable payment number assigned by the system.", example = "PMT-2026-0031")
        String paymentNumber,

        @Schema(description = "Customer the payment was received from.",
                example = "6b1e9c22-9f8a-4a1b-9c0e-1d2f3a4b5c6d")
        UUID customerId,

        @Schema(description = "Customer name captured on the payment.", example = "Asset Homes Pvt Ltd")
        String customerName,

        @Schema(description = "Date the payment was received.", example = "2026-08-10")
        LocalDate paymentDate,

        @Schema(description = "Total amount received.", example = "20000.00")
        BigDecimal amount,

        @Schema(description = "Company bank account the payment was deposited into.",
                example = "c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f")
        UUID companyBankAccountId,

        @Schema(description = "Name of the receiving bank.", example = "HDFC Bank")
        String bankName,

        @Schema(description = "Receiving bank account number.", example = "50100123456789")
        String bankAccountNumber,

        @Schema(description = "External reference for the payment, such as a UTR or cheque number.",
                example = "UTR2026081012345")
        String externalReference,

        @Schema(description = "Ledger journal entry posted for the receipt.",
                example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10")
        UUID journalEntryId,

        @Schema(description = "Internal notes.", example = "Part payment against August invoices")
        String notes,

        @Schema(description = "How the payment amount was allocated across invoices.")
        List<AllocationDto> allocations
) {
    @Schema(description = "Allocation of part of a payment to a single invoice.")
    public record AllocationDto(
            @Schema(description = "Unique allocation id.", example = "d4e5f6a7-b8c9-4d0e-1f2a-3b4c5d6e7f80")
            UUID id,

            @Schema(description = "Invoice the amount was applied to.",
                    example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
            UUID invoiceId,

            @Schema(description = "Number of the invoice the amount was applied to.", example = "INV-2026-0042")
            String invoiceNumber,

            @Schema(description = "Amount of the payment applied to this invoice.", example = "12000.00")
            BigDecimal allocatedAmount
    ) {}
}
