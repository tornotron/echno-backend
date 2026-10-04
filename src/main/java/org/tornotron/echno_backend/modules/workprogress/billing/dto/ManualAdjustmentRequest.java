package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentEffect;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionKind;

@Schema(description = "One adjustment entered by hand on a bill: an approved variation, an extra item, escalation, or another addition or deduction.")
public record ManualAdjustmentRequest(
        @Schema(example = "VARIATION")
        @NotNull DeductionKind kind,
        @Schema(example = "Approved variation VO-03")
        @NotBlank @Size(max = 120) String label,
        @Schema(description = "ADD or DEDUCT; defaults by kind.", nullable = true)
        AdjustmentEffect effect,
        @Schema(description = "In rupees.")
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal amount
) {}
