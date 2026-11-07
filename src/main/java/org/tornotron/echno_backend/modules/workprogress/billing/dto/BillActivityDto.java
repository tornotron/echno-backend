package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillEventType;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillingModel;

@Schema(description = "One recent event on a bill, with the bill and project it belongs to.")
public record BillActivityDto(
        UUID id,
        UUID billId,
        String billNumber,
        BillingModel billingModel,
        Long subContractId,
        @Schema(nullable = true)
        String contractName,
        Long projectId,
        @Schema(nullable = true)
        String projectName,
        BillEventType type,
        @Schema(nullable = true)
        String note,
        @Schema(nullable = true)
        String actorName,
        LocalDateTime createdAt
) {}
