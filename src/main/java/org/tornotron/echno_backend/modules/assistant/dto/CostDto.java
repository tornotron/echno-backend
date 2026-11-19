package org.tornotron.echno_backend.modules.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What answering this question cost in language-model tokens.")
public record CostDto(
        int promptTokens,
        int completionTokens,
        int embeddingTokens,
        @Schema(description = "Estimated cost in millionths of the billing currency; zero when no price is configured.") long estimatedCostMicros
) {
    public CostDto(int promptTokens, int completionTokens, long estimatedCostMicros) {
        this(promptTokens, completionTokens, 0, estimatedCostMicros);
    }
}
