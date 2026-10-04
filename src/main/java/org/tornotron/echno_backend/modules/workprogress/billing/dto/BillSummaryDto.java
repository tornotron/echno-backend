package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillingModel;

@Schema(description = "A bill as a row in a list.")
public record BillSummaryDto(
        UUID id,
        Long subContractId,
        String contractName,
        String contractorName,
        Long projectId,
        @Schema(nullable = true)
        String projectName,
        String billNumber,
        BillingModel billingModel,
        BillStatus status,
        @Schema(nullable = true)
        LocalDate periodFrom,
        @Schema(nullable = true)
        LocalDate periodTo,
        @Schema(nullable = true)
        Long contractMilestoneId,
        @Schema(nullable = true)
        String milestoneName,
        @Schema(description = "The amount claimed, in rupees.")
        BigDecimal grossClaimed,
        @Schema(description = "The gross certified, once the bill is certified.", nullable = true)
        BigDecimal grossCertified,
        @Schema(description = "The net payable, once the bill is certified.", nullable = true)
        BigDecimal netPayable,
        @Schema(nullable = true)
        LocalDateTime submittedAt,
        @Schema(nullable = true)
        LocalDateTime approvedAt,
        LocalDateTime createdAt
) {}
