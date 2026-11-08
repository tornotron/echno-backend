package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmBudget;

@Schema(description = "What reading a package with the AI service has used, against the package's and the month's limits.")
public record LlmSpendDto(
        long packageCalls,
        long packageTokens,
        @Schema(description = "Estimated from the configured rates; zero when they are not set") BigDecimal packageCostUsd,
        long packageTokenLimit,
        BigDecimal packageUsdLimit,
        long monthCalls,
        long monthTokens,
        BigDecimal monthCostUsd,
        long monthTokenLimit,
        BigDecimal monthUsdLimit,
        @Schema(description = "Whether rates are configured, so the dollar limits apply; the token limits always do")
        boolean pricesKnown,
        @Schema(description = "Why no further call fits, or null", nullable = true) String exceeded) {

    public static LlmSpendDto of(LlmBudget.Standing s) {
        return new LlmSpendDto(s.packageCalls(), s.packageTokens(), s.packageUsd(), s.packageTokenLimit(),
                s.packageUsdLimit(), s.monthCalls(), s.monthTokens(), s.monthUsd(), s.monthTokenLimit(), s.monthUsdLimit(),
                s.pricesKnown(), s.exceeded().orElse(null));
    }
}
