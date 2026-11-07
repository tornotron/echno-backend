package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ProjectBillingState;

@Schema(description = "One project on the billing home page: its contracts and bills added up.")
public record ProjectBillingSummaryDto(
        Long projectId,
        String projectName,
        @Schema(nullable = true)
        String city,
        @Schema(nullable = true)
        String state,
        @Schema(description = "The project's own status.", nullable = true)
        String projectStatus,
        @Schema(description = "The project's inspected progress, as a percent.", nullable = true)
        Double progress,
        int contractCount,
        @Schema(description = "Sum of the contract values, in rupees.")
        BigDecimal contractValue,
        @Schema(description = "Bills other than cancelled ones.")
        long billCount,
        @Schema(description = "Bills not yet approved or cancelled.")
        long openBills,
        @Schema(description = "Submitted, verified or certified bills waiting for the next signature.")
        long awaitingSignature,
        @Schema(description = "Gross certified on certified and approved bills, in rupees.")
        BigDecimal certifiedToDate,
        @Schema(description = "Net payable of approved bills, in rupees.")
        BigDecimal netApprovedToDate,
        @Schema(description = "Paid against those bills' payables, in rupees.")
        BigDecimal paidToDate,
        @Schema(description = "Still due on those bills' payables, in rupees.")
        BigDecimal amountDue,
        @Schema(description = "certifiedToDate as a percent of the contract value.", nullable = true)
        BigDecimal billedPercent,
        ProjectBillingState billingState,
        @Schema(description = "When a bill on the project last changed.", nullable = true)
        LocalDateTime lastActivityAt
) {}
