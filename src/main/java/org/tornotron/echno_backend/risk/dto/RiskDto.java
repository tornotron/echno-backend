package org.tornotron.echno_backend.risk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.tornotron.echno_backend.risk.ProjectRisk;

/** One risk on a project's risk register, as read back. */
@Schema(description = "A risk on a project's risk register.")
public record RiskDto(
        @Schema(description = "Id of the risk.", example = "4f1c0d6e-2b7a-4c55-9d38-1a2b3c4d5e6f")
        UUID id,
        @Schema(description = "Id of the project.", example = "42")
        Long projectId,
        @Schema(description = "The project's running number for the risk.", example = "7")
        Integer riskNumber,
        @Schema(description = "The running number as shown, R- and three digits.", example = "R-007")
        String riskId,
        @Schema(description = "Short title of the risk.", example = "Late approval of revised structural drawings")
        String title,
        @Schema(nullable = true, description = "The risk in detail.")
        String description,
        @Schema(description = "Risk category code.", example = "design-engineering")
        String category,
        @Schema(nullable = true, description = "Sub-category within the category, standard or free text.",
                example = "Incomplete or delayed design")
        String subCategory,
        @Schema(description = "Where the risk stands.", example = "identified")
        String status,
        @Schema(nullable = true, description = "Who owns the risk, as a name.", example = "Ravi Kumar")
        String owner,
        @Schema(description = "Likelihood before any response.", example = "medium")
        String probability,
        @Schema(description = "Consequence before any response.", example = "major")
        String impact,
        @Schema(description = "Probability score times impact score, 1 to 25.", example = "12")
        Integer riskScore,
        @Schema(description = "Likelihood once the response is in place.", example = "low")
        String residualProbability,
        @Schema(description = "Consequence once the response is in place.", example = "minor")
        String residualImpact,
        @Schema(description = "Residual probability score times residual impact score, 1 to 25.", example = "4")
        Integer residualScore,
        @Schema(description = "How the risk is being handled.", example = "mitigate")
        String responseType,
        @Schema(nullable = true, description = "What happens if the risk occurs.")
        String contingencyPlan,
        @Schema(nullable = true, description = "When the risk was identified.", example = "2026-10-01")
        LocalDate identifiedDate,
        @Schema(nullable = true, description = "When the risk is next reviewed.", example = "2026-10-15")
        LocalDate reviewDate,
        @Schema(nullable = true, description = "When the risk was closed.")
        LocalDate closedDate,
        @Schema(nullable = true, description = "Estimated cost if the risk occurs.", example = "250000.00")
        BigDecimal costImpact,
        @Schema(nullable = true, description = "Potential delay in days if the risk occurs.", example = "14")
        Integer scheduleImpact,
        @Schema(description = "Version for optimistic locking; send it back on an update.", example = "0")
        Long version,
        @Schema(description = "When the risk was recorded.")
        Instant createdAt,
        @Schema(description = "When the risk was last changed.")
        Instant updatedAt) {

    public static RiskDto from(ProjectRisk risk) {
        return new RiskDto(risk.getId(), risk.getProjectId(), risk.getRiskNumber(),
                displayNumber(risk.getRiskNumber()), risk.getTitle(), risk.getDescription(),
                risk.getCategory(), risk.getSubCategory(), risk.getStatus(), risk.getOwner(),
                risk.getProbability(), risk.getImpact(), risk.getRiskScore(),
                risk.getResidualProbability(), risk.getResidualImpact(), risk.getResidualScore(),
                risk.getResponseType(), risk.getContingencyPlan(), risk.getIdentifiedDate(),
                risk.getReviewDate(), risk.getClosedDate(), risk.getCostImpact(),
                risk.getScheduleImpactDays(), risk.getVersion(), risk.getCreatedAt(), risk.getUpdatedAt());
    }

    /** {@code R-007}; past 999 the number simply grows, {@code R-1000}. */
    public static String displayNumber(Integer number) {
        return String.format("R-%03d", number);
    }
}
