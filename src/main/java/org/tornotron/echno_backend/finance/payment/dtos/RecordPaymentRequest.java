package org.tornotron.echno_backend.finance.payment.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Payload to record a customer payment and allocate it across outstanding invoices. "
        + "The allocations must sum to the payment amount.")
public record RecordPaymentRequest(
        @Schema(description = "Customer the payment was received from.",
                example = "6b1e9c22-9f8a-4a1b-9c0e-1d2f3a4b5c6d")
        @NotNull UUID customerId,

        @Schema(description = "Date the payment was received.", example = "2026-08-10")
        @NotNull LocalDate paymentDate,

        @Schema(description = "Total amount received. Must be greater than zero.", example = "20000.00")
        @NotNull @DecimalMin(value = "0.0001") BigDecimal amount,

        @Schema(description = "Company bank account the payment was deposited into.",
                example = "c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f")
        @NotNull UUID companyBankAccountId,

        @Schema(description = "External reference for the payment, such as a UTR or cheque number.",
                example = "UTR2026081012345")
        @Size(max = 100) String externalReference,

        @Schema(description = "Internal notes.", example = "Part payment against August invoices")
        @Size(max = 500) String notes,

        @Schema(description = "How to allocate the amount across invoices. At least one allocation is required.")
        @NotNull @Size(min = 1) @Valid List<AllocationRequest> allocations
) {
    @Schema(description = "Allocation of part of the payment to a single invoice.")
    public record AllocationRequest(
            @Schema(description = "Invoice to apply the amount to.",
                    example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
            @NotNull UUID invoiceId,

            @Schema(description = "Amount to apply to this invoice. Must be greater than zero.", example = "12000.00")
            @NotNull @DecimalMin(value = "0.0001") BigDecimal allocatedAmount
    ) {}
}
