package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.LlmCall;
import org.tornotron.echno_backend.modules.documentingestion.domain.LlmCallOutcome;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkPages;

@Schema(description = "One call that sent pages of a document to the outside AI service. Holds no document text.")
public record LlmCallDto(
        UUID id,
        UUID packageId,
        UUID documentId,
        UUID jobId,
        String extractorKey,
        String chunkKey,
        int callNumber,
        @Schema(description = "The pages that were sent") List<Integer> pages,
        @Schema(nullable = true) String model,
        @Schema(nullable = true) String modelVersion,
        String promptVersion,
        @Schema(nullable = true) Integer promptTokens,
        @Schema(nullable = true) Integer completionTokens,
        @Schema(description = "Null when the configured rates are zero", nullable = true) BigDecimal estimatedCostUsd,
        @Schema(nullable = true) String finishReason,
        long latencyMs,
        LlmCallOutcome outcome,
        LocalDateTime calledAt) {

    public static LlmCallDto of(LlmCall c) {
        return new LlmCallDto(c.getId(), c.getPackageId(), c.getDocumentId(), c.getJobId(), c.getExtractorKey(),
                c.getChunkKey(), c.getCallNumber(), ChunkPages.parse(c.getPages()), c.getModel(), c.getModelVersion(),
                c.getPromptVersion(), c.getPromptTokens(), c.getCompletionTokens(), c.getEstimatedCostUsd(),
                c.getFinishReason(), c.getLatencyMs(), c.getOutcome(), c.getCalledAt());
    }
}
