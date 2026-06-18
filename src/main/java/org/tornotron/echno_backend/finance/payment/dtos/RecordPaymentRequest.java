package org.tornotron.echno_backend.finance.payment.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RecordPaymentRequest(
        @NotNull UUID customerId,
        @NotNull LocalDate paymentDate,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal amount,
        @NotNull UUID companyBankAccountId,
        @Size(max = 100) String externalReference,
        @Size(max = 500) String notes,
        @NotNull @Size(min = 1) @Valid List<AllocationRequest> allocations
) {
    public record AllocationRequest(
            @NotNull UUID invoiceId,
            @NotNull @DecimalMin(value = "0.0001") BigDecimal allocatedAmount
    ) {}
}
