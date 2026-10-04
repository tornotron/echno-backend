package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillLineStatus;

@Schema(description = "One BOQ item on a running account bill, with the running account worked out.")
public record BillLineDto(
        UUID id,
        UUID boqItemId,
        String itemCode,
        String description,
        String unit,
        BigDecimal contractQuantity,
        BigDecimal rate,
        @Schema(description = "Accepted on earlier certified bills.")
        BigDecimal previousQuantity,
        @Schema(description = "Claimed on this bill.")
        BigDecimal claimedQuantity,
        @Schema(description = "Found at the joint measurement.", nullable = true)
        BigDecimal measuredQuantity,
        @Schema(description = "Accepted for payment on this bill.", nullable = true)
        BigDecimal acceptedQuantity,
        @Schema(description = "Previous plus this bill (accepted where measured, claimed otherwise).")
        BigDecimal cumulativeQuantity,
        @Schema(description = "Contract quantity less the cumulative quantity.")
        BigDecimal balanceQuantity,
        @Schema(description = "Cumulative quantity as a percent of the contract quantity.")
        BigDecimal percentComplete,
        @Schema(description = "This bill's quantity times the rate, in rupees.")
        BigDecimal thisAmount,
        @Schema(description = "Cumulative quantity times the rate, in rupees.")
        BigDecimal cumulativeAmount,
        BillLineStatus status,
        @Schema(description = "A quantity to claim, from the inspected progress of the item's schedule activity.", nullable = true)
        BigDecimal suggestedQuantity,
        @Schema(nullable = true)
        String remarks
) {}
