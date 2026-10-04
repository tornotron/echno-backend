package org.tornotron.echno_backend.risk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.tornotron.echno_backend.risk.RiskScale;

/**
 * A risk as the register form submits it, for a create, an update or one line of an import. The
 * scores and the risk number are worked out by the server.
 */
@Schema(description = "A risk on a project's risk register, as submitted. Scores and the R-number are "
        + "derived by the server.")
public record RiskRequest(
        @Schema(description = "Short title of the risk.", example = "Late approval of revised structural drawings")
        @NotBlank(message = "title is required")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @Schema(nullable = true, description = "The risk in detail.", example = "Revised drawings for the podium slab are awaited.")
        @Size(max = 4000, message = "description must be at most 4000 characters")
        String description,

        @Schema(description = "Risk category code. One of the construction risk categories, or one of the "
                + "earlier generic codes a risk recorded before them still carries.", example = "design-engineering")
        @NotBlank(message = "category is required")
        @Pattern(regexp = RiskScale.CATEGORY_PATTERN, message = "category is not a known risk category")
        String category,

        @Schema(nullable = true, description = "Sub-category within the category: one of the standard "
                + "sub-categories or free text. Optional.", example = "Incomplete or delayed design")
        @Size(max = 255, message = "subCategory must be at most 255 characters")
        String subCategory,

        @Schema(description = "Where the risk stands.", example = "identified")
        @NotBlank(message = "status is required")
        @Pattern(regexp = RiskScale.STATUS_PATTERN, message = "status is not a known risk status")
        String status,

        @Schema(nullable = true, description = "Who owns the risk, as a name.", example = "Ravi Kumar")
        @Size(max = 255, message = "owner must be at most 255 characters")
        String owner,

        @Schema(description = "Likelihood before any response.", example = "medium")
        @NotBlank(message = "probability is required")
        @Pattern(regexp = RiskScale.PROBABILITY_PATTERN, message = "probability is not a known value")
        String probability,

        @Schema(description = "Consequence before any response.", example = "major")
        @NotBlank(message = "impact is required")
        @Pattern(regexp = RiskScale.IMPACT_PATTERN, message = "impact is not a known value")
        String impact,

        @Schema(description = "Likelihood once the response is in place.", example = "low")
        @NotBlank(message = "residualProbability is required")
        @Pattern(regexp = RiskScale.PROBABILITY_PATTERN, message = "residualProbability is not a known value")
        String residualProbability,

        @Schema(description = "Consequence once the response is in place.", example = "minor")
        @NotBlank(message = "residualImpact is required")
        @Pattern(regexp = RiskScale.IMPACT_PATTERN, message = "residualImpact is not a known value")
        String residualImpact,

        @Schema(description = "How the risk is being handled.", example = "mitigate")
        @NotBlank(message = "responseType is required")
        @Pattern(regexp = RiskScale.RESPONSE_PATTERN, message = "responseType is not a known value")
        String responseType,

        @Schema(nullable = true, description = "What happens if the risk occurs.", example = "Re-sequence podium works.")
        @Size(max = 4000, message = "contingencyPlan must be at most 4000 characters")
        String contingencyPlan,

        @Schema(nullable = true, description = "When the risk was identified.", example = "2026-10-01")
        LocalDate identifiedDate,

        @Schema(nullable = true, description = "When the risk is next reviewed.", example = "2026-10-15")
        LocalDate reviewDate,

        @Schema(nullable = true, description = "When the risk was closed.", example = "2026-11-02")
        LocalDate closedDate,

        @Schema(nullable = true, description = "Estimated cost if the risk occurs.", example = "250000.00")
        @DecimalMin(value = "0", message = "costImpact cannot be negative")
        @Digits(integer = 17, fraction = 2, message = "costImpact must have at most 17 digits and 2 decimals")
        BigDecimal costImpact,

        @Schema(nullable = true, description = "Potential delay in days if the risk occurs.", example = "14")
        @Min(value = 0, message = "scheduleImpact cannot be negative")
        @Max(value = 36500, message = "scheduleImpact must be at most 36500 days")
        Integer scheduleImpact,

        @Schema(nullable = true, description = "On an update, the version the editor started from. When it no "
                + "longer matches, someone else saved first and the update is refused with a 409. Ignored "
                + "on a create or an import.", example = "3")
        Long version,

        @Schema(nullable = true, description = "On an import, the risk's id in the browser storage it came "
                + "from. A risk with a reference the project already holds is skipped, so a repeated import "
                + "adds nothing. Ignored on a create or an update.", example = "4f1c0d6e-2b7a-4c55-9d38-1a2b3c4d5e6f")
        @Size(max = 64, message = "importRef must be at most 64 characters")
        String importRef) {
}
