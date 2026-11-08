package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.risk.RiskScale;

/**
 * Optional choices for a risk raised from a contract term or an obligation. Anything left out takes the
 * default: a title naming the term or obligation and its clause, category {@code contractual-legal},
 * probability {@code medium}, impact {@code moderate}, response {@code mitigate}.
 */
@Schema(description = "Optional choices for the risk raised from a contract term or an obligation; every field may "
        + "be left out.")
public record RaiseRiskRequest(
        @Schema(nullable = true, example = "Liquidated damages on late handover")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @Schema(nullable = true, example = "contractual-legal")
        @Pattern(regexp = RiskScale.CATEGORY_PATTERN, message = "category is not a known risk category")
        String category,

        @Schema(nullable = true, example = "medium")
        @Pattern(regexp = RiskScale.PROBABILITY_PATTERN, message = "probability is not a known value")
        String probability,

        @Schema(nullable = true, example = "major")
        @Pattern(regexp = RiskScale.IMPACT_PATTERN, message = "impact is not a known value")
        String impact,

        @Schema(nullable = true, example = "mitigate")
        @Pattern(regexp = RiskScale.RESPONSE_PATTERN, message = "responseType is not a known value")
        String responseType,

        @Schema(nullable = true, example = "Site manager")
        @Size(max = 255, message = "owner must be at most 255 characters")
        String owner) {

    public static RaiseRiskRequest defaults() {
        return new RaiseRiskRequest(null, null, null, null, null, null);
    }
}
