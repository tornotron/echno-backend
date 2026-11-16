package org.tornotron.echno_backend.modules.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.tornotron.echno_backend.modules.assistant.domain.AskOutcome;

@Schema(description = "One audited question: what was asked, how it ended, what was looked at, and what it cost.")
public record QueryLogDto(
        UUID id,
        Long userId,
        Long projectId,
        String question,
        AskOutcome outcome,
        @Schema(description = "The statements, clarification or decline as shown to the person.") Map<String, Object> answer,
        List<String> providersSelected,
        List<String> evidenceIds,
        String llmModel,
        String llmModelVersion,
        int promptTokens,
        int completionTokens,
        long estimatedCostMicros,
        long latencyMs,
        Instant createdAt
) {}
