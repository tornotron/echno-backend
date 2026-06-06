package org.tornotron.echno_backend.finance.invoice.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateInvoiceRequest(
        @NotNull UUID customerId,
        @NotNull LocalDate invoiceDate,
        @NotNull LocalDate dueDate,
        @Size(max = 500) String notes,
        @NotNull @Size(min = 1) @Valid List<LineRequest> lines
) {
    public record LineRequest(
            @NotBlank @Size(max = 500) String description,
            @NotNull @DecimalMin(value = "0.0001") BigDecimal quantity,
            @NotNull @DecimalMin(value = "0.0") BigDecimal unitPrice,
            @NotNull @DecimalMin(value = "0.0") @DecimalMax(value = "100.0") BigDecimal taxRate,
            @NotNull UUID revenueAccountId
    ) {}
}