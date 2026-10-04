package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "One line of a contract's bill of quantities. The amount is worked out as quantity times rate.")
public record BoqItemRequest(
        @Schema(description = "The item code, unique within the contract.", example = "CP-01")
        @NotBlank @Size(max = 50) String itemCode,
        @Schema(description = "What the item is.", example = "RCC in foundation (M25)")
        @NotBlank @Size(max = 4000) String description,
        @Schema(description = "The unit of measurement.", example = "m3")
        @NotBlank @Size(max = 30) String unit,
        @Schema(description = "The quantity the contract covers.", example = "500.000")
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 3) BigDecimal contractQuantity,
        @Schema(description = "The rate per unit, in rupees.", example = "8500.00")
        @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal rate,
        @Schema(description = "A schedule activity of the contract's project whose inspected progress suggests a quantity to claim.", nullable = true)
        Long wbsElementId,
        @Schema(description = "Position in the BOQ; lower first.", nullable = true)
        Integer sortOrder
) {}
