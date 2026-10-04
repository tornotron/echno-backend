package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.RequirementStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.RequirementType;

@Schema(description = "One thing a contract milestone needs before it can be certified for payment.")
public record MilestoneRequirementDto(
        UUID id,
        Long subContractId,
        Long contractMilestoneId,
        String title,
        RequirementType type,
        @Schema(nullable = true)
        String description,
        boolean mandatory,
        @Schema(nullable = true)
        LocalDate dueDate,
        RequirementStatus status,
        @Schema(nullable = true)
        String remarks,
        int sortOrder
) {}
