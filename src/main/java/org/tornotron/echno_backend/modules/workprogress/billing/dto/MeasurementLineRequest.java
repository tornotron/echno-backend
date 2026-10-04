package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "The joint measurement of one line of a running account bill.")
public record MeasurementLineRequest(
        @NotNull UUID lineId,
        @Schema(description = "Quantity found on site.", nullable = true)
        @DecimalMin("0") @Digits(integer = 15, fraction = 3) BigDecimal measuredQuantity,
        @Schema(description = "Quantity accepted for payment; not more than claimed. Zero rejects the line.", nullable = true)
        @DecimalMin("0") @Digits(integer = 15, fraction = 3) BigDecimal acceptedQuantity,
        @Schema(nullable = true)
        @Size(max = 2000) String remarks
) {}
