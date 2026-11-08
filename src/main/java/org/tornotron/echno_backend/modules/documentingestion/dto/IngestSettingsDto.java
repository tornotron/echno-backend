package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "The organization's document ingestion settings, with the sentence the screen shows beside the switch.")
public record IngestSettingsDto(
        @Schema(description = "Whether the text of tender files may be sent to the outside AI service to be read. On unless an admin turned it off")
        boolean llmReadingEnabled,
        @Schema(description = "What turning it on means, in plain words, for the screen to show as is")
        String llmReadingDisclosure,
        @Schema(description = "Who last changed it, or null while it is at its default", nullable = true) Long changedBy,
        @Schema(nullable = true) LocalDateTime changedAt,
        @Schema(description = "The monthly AI reading limit in estimated dollars; null means the configured default", nullable = true)
        BigDecimal monthlyBudgetUsd,
        @Schema(description = "The monthly AI reading limit in tokens; null means the configured default", nullable = true)
        Long monthlyBudgetTokens,
        @Schema(description = "The most recent changes, newest first") List<Change> recentChanges) {

    public record Change(String setting, String oldValue, String newValue, Long changedBy, LocalDateTime changedAt) {
    }
}
