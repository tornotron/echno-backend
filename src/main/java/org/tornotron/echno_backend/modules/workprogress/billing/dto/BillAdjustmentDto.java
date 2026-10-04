package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentEffect;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentSource;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionBasis;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionKind;

@Schema(description = "One commercial adjustment on a bill. Before certification the rule lines are a preview worked out from the contract's rules.")
public record BillAdjustmentDto(
        @Schema(description = "Null on a preview line.", nullable = true)
        UUID id,
        @Schema(nullable = true)
        UUID ruleId,
        DeductionKind kind,
        String label,
        AdjustmentEffect effect,
        DeductionBasis basis,
        @Schema(nullable = true)
        BigDecimal rate,
        BigDecimal amount,
        AdjustmentSource source,
        @Schema(description = "True for a rule line worked out on read, before certification freezes it.")
        boolean preview
) {}
