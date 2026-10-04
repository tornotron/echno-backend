package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentEffect;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionBasis;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionKind;

@Schema(description = "A commercial adjustment the contract applies to every certified bill.")
public record DeductionRuleRequest(
        @Schema(description = "RETENTION, ADVANCE_RECOVERY, PENALTY_LD, TDS, GST, VARIATION, EXTRA_ITEM, ESCALATION or OTHER.", example = "RETENTION")
        @NotNull DeductionKind kind,
        @Schema(description = "How the line reads on the bill.", example = "Retention 5%")
        @NotBlank @Size(max = 120) String label,
        @Schema(description = "ADD or DEDUCT. Defaults to ADD for GST, variations, extra items and escalation, DEDUCT otherwise.", nullable = true)
        AdjustmentEffect effect,
        @Schema(description = "PERCENT of the bill's base, or a FIXED amount per bill.", example = "PERCENT")
        @NotNull DeductionBasis basis,
        @Schema(description = "The percent, for a PERCENT rule.", example = "5", nullable = true)
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") @Digits(integer = 3, fraction = 3) BigDecimal rate,
        @Schema(description = "The amount per bill in rupees, for a FIXED rule.", nullable = true)
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal fixedAmount,
        @Schema(description = "The most the rule may take over the life of the contract, in rupees.", nullable = true)
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal capAmount,
        @Schema(description = "Whether the rule applies to the next certification. Defaults to true.", nullable = true)
        Boolean enabled,
        @Schema(description = "Position on the bill; lower first.", nullable = true)
        Integer sortOrder
) {}
