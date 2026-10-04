package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "A contract milestone as billing sees it: its value, how much of it is certified, and its requirements.")
public record BillingMilestoneDto(
        Long id,
        String name,
        @Schema(nullable = true)
        String description,
        @Schema(nullable = true)
        LocalDate targetDate,
        @Schema(nullable = true)
        LocalDate completionDate,
        @Schema(nullable = true)
        String status,
        @Schema(nullable = true)
        BigDecimal paymentPercentage,
        @Schema(description = "The milestone amount, or its payment percentage of the contract value; null when neither is recorded.", nullable = true)
        BigDecimal value,
        @Schema(description = "Percent of the milestone certified on certified and approved bills.")
        BigDecimal certifiedPercent,
        List<MilestoneRequirementDto> requirements
) {}
