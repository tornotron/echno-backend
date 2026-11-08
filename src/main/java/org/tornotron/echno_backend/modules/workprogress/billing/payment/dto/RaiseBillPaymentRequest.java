package org.tornotron.echno_backend.modules.workprogress.billing.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "Raises a payment on an approved bill, for the second signature.")
public record RaiseBillPaymentRequest(
        @Schema(description = "Rupees, up to the amount due. Less than that is a part payment.", example = "250000.00")
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal amount,
        @Schema(nullable = true)
        @Size(max = 2000) String note
) {}
