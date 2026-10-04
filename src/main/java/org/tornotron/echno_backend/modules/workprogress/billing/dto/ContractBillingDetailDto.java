package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Everything billing holds for one contract: its BOQ, deduction rules, milestones and bills.")
public record ContractBillingDetailDto(
        ContractBillingSummaryDto summary,
        @Schema(description = "The retention percentage on the contract record.", nullable = true)
        BigDecimal retentionPercentage,
        @Schema(description = "The mobilization advance on the contract record.", nullable = true)
        BigDecimal mobilizationAdvance,
        List<BoqItemDto> boqItems,
        @Schema(description = "Sum of the BOQ amounts, in rupees.")
        BigDecimal boqTotal,
        List<DeductionRuleDto> deductionRules,
        List<BillingMilestoneDto> milestones,
        List<BillSummaryDto> bills
) {}
