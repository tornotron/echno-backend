package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "The quantity claimed on one line of a running account bill.")
public record ClaimLineRequest(
        @NotNull UUID lineId,
        @Schema(description = "Quantity claimed on this bill; previous plus claimed may not pass the contract quantity.")
        @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 3) BigDecimal claimedQuantity,
        @Schema(nullable = true)
        @Size(max = 2000) String remarks
) {}
