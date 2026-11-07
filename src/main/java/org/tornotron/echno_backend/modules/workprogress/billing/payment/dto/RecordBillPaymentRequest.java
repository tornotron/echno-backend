package org.tornotron.echno_backend.modules.workprogress.billing.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

@Schema(description = "Says an authorized manual payment was made from the bank.")
public record RecordBillPaymentRequest(
        @Schema(description = "The bank's reference for the transfer.", example = "HDFCR52026110812345")
        @NotBlank @Size(max = 100) String bankReference,
        @Schema(description = "The day it was paid; not in the future.")
        @NotNull LocalDate paidOn
) {}
