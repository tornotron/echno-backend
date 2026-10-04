package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentEffect;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionBasis;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionKind;

@Schema(description = "A commercial adjustment the contract applies to every certified bill, with what it has taken so far.")
public record DeductionRuleDto(
        UUID id,
        Long subContractId,
        DeductionKind kind,
        String label,
        AdjustmentEffect effect,
        DeductionBasis basis,
        @Schema(nullable = true)
        BigDecimal rate,
        @Schema(nullable = true)
        BigDecimal fixedAmount,
        @Schema(nullable = true)
        BigDecimal capAmount,
        boolean enabled,
        int sortOrder,
        @Schema(description = "What the rule has added or deducted on certified and approved bills so far, in rupees.")
        BigDecimal appliedToDate
) {}
