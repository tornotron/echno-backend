package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillingModel;

@Schema(description = "One contract on the billing home page: its billing model, what is certified so far and its open bill.")
public record ContractBillingSummaryDto(
        Long subContractId,
        @Schema(description = "The contract's own reference.", nullable = true)
        String contractRef,
        String contractName,
        String contractorName,
        @Schema(nullable = true)
        Long projectId,
        @Schema(nullable = true)
        String projectName,
        @Schema(nullable = true)
        BigDecimal contractValue,
        @Schema(nullable = true)
        String contractStatus,
        @Schema(description = "Fixed by the first bill; null while billing has not started.", nullable = true)
        BillingModel billingModel,
        @Schema(description = "Bills other than cancelled ones.")
        int billCount,
        int approvedBillCount,
        @Schema(description = "Gross certified on certified and approved bills, in rupees.")
        BigDecimal certifiedToDate,
        @Schema(description = "Net payable of approved bills, in rupees.")
        BigDecimal netApprovedToDate,
        @Schema(description = "certifiedToDate as a percent of the contract value.", nullable = true)
        BigDecimal billedPercent,
        @Schema(description = "The bill still in progress, if any.", nullable = true)
        BillSummaryDto openBill,
        @Schema(nullable = true)
        LocalDateTime lastApprovedAt
) {}
