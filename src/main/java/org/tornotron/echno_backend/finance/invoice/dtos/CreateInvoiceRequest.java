package org.tornotron.echno_backend.finance.invoice.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Payload to create a draft customer invoice. Line and header totals are computed "
        + "server side from the line inputs.")
public record CreateInvoiceRequest(
        @Schema(description = "Customer the invoice is billed to.", example = "6b1e9c22-9f8a-4a1b-9c0e-1d2f3a4b5c6d")
        @NotNull UUID customerId,

        @Schema(description = "Date the invoice was issued.", example = "2026-08-01")
        @NotNull LocalDate invoiceDate,

        @Schema(description = "Date payment is due.", example = "2026-08-31")
        @NotNull LocalDate dueDate,

        @Schema(description = "Internal notes about the invoice.", example = "First milestone billing")
        @Size(max = 500) String notes,

        @Schema(description = "Invoice line items. At least one line is required.")
        @NotNull @Size(min = 1) @Valid List<LineRequest> lines
) {
    /**
     * Named rather than left to the simple name, which {@code PostJournalRequest.LineRequest}
     * also carries. Two schemas cannot share one name: the journal line won, so the document
     * described an invoice line as an account id with a debit and a credit, and a client
     * following it sent a body this endpoint rejects. Nothing here changes about the journal
     * line, which keeps the name it had.
     */
    @Schema(name = "InvoiceLineRequest", description = "A single line on a customer invoice.")
    public record LineRequest(
            @Schema(description = "What the line is charging for.", example = "Structural design consultancy")
            @NotBlank @Size(max = 500) String description,

            @Schema(description = "Quantity billed. Must be greater than zero.", example = "10")
            @NotNull @DecimalMin(value = "0.0001") BigDecimal quantity,

            @Schema(description = "Price per unit.", example = "5000.00")
            @NotNull @DecimalMin(value = "0.0") BigDecimal unitPrice,

            @Schema(description = "Tax rate applied to the line, as a percentage between 0 and 100.", example = "18.0")
            @NotNull @DecimalMin(value = "0.0") @DecimalMax(value = "100.0") BigDecimal taxRate,

            @Schema(description = "Revenue account the line is credited to.",
                    example = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
            @NotNull UUID revenueAccountId
    ) {}
}