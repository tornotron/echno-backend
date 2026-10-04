package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillingModel;

@Schema(description = "A running account or milestone bill with its lines, adjustments and the running account worked out. Before certification the totals are worked out on read; from certification on they are as frozen.")
public record BillDto(
        UUID id,
        Long projectId,
        @Schema(nullable = true)
        String projectName,
        Long subContractId,
        @Schema(nullable = true)
        String contractRef,
        String contractName,
        String contractorName,
        @Schema(nullable = true)
        BigDecimal contractValue,
        BillingModel billingModel,
        String billNumber,
        BillStatus status,
        @Schema(nullable = true)
        LocalDate periodFrom,
        @Schema(nullable = true)
        LocalDate periodTo,
        @Schema(nullable = true)
        Long contractMilestoneId,
        @Schema(nullable = true)
        String milestoneName,
        @Schema(nullable = true)
        LocalDate milestoneTargetDate,
        @Schema(description = "The milestone value, in rupees.", nullable = true)
        BigDecimal milestoneValue,
        @Schema(description = "Percent of the milestone certified on earlier bills.", nullable = true)
        BigDecimal milestoneCertifiedBeforePercent,
        @Schema(nullable = true)
        BigDecimal claimedPercent,
        @Schema(nullable = true)
        BigDecimal certifiedPercent,
        @Schema(nullable = true)
        String contractorReference,
        @Schema(nullable = true)
        String location,
        @Schema(nullable = true)
        LocalDate measurementDate,
        @Schema(nullable = true)
        String measuredBy,
        @Schema(nullable = true)
        String clientRepresentative,
        @Schema(nullable = true)
        String remarks,
        @Schema(description = "Why the bill was last returned for correction.", nullable = true)
        String returnReason,
        List<BillLineDto> lines,
        @Schema(description = "The requirements of the milestone, for a milestone bill.")
        List<MilestoneRequirementDto> requirements,
        List<BillAdjustmentDto> adjustments,
        @Schema(description = "The amount claimed, in rupees.")
        BigDecimal grossClaimed,
        @Schema(description = "The gross for this bill: accepted where measured, claimed otherwise; as certified once certified.")
        BigDecimal grossAmount,
        @Schema(description = "Sum of the additions, in rupees.")
        BigDecimal additionsTotal,
        @Schema(description = "Sum of the deductions, in rupees.")
        BigDecimal deductionsTotal,
        @Schema(description = "Gross plus additions less deductions, in rupees.")
        BigDecimal netPayable,
        @Schema(description = "Gross certified on the contract's earlier bills, in rupees.")
        BigDecimal previousCertified,
        @Schema(description = "Previous certified plus this bill's gross, in rupees.")
        BigDecimal cumulativeCertified,
        @Schema(description = "True once the bill is certified and its figures are frozen.")
        boolean amountsFinal,
        @Schema(nullable = true)
        String preparedByName,
        @Schema(nullable = true)
        String submittedByName,
        @Schema(nullable = true)
        LocalDateTime submittedAt,
        @Schema(nullable = true)
        String verifiedByName,
        @Schema(nullable = true)
        LocalDateTime verifiedAt,
        @Schema(nullable = true)
        String certifiedByName,
        @Schema(nullable = true)
        LocalDateTime certifiedAt,
        @Schema(nullable = true)
        String approvedByName,
        @Schema(nullable = true)
        LocalDateTime approvedAt,
        @Schema(description = "True when the approver also certified the bill, under the system-admin role.")
        boolean selfApproved,
        @Schema(description = "The payable handed to finance on approval.", nullable = true)
        Long payableId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
