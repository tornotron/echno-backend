package org.tornotron.echno_backend.modules.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.tornotron.echno_backend.modules.assistant.api.SourceRef;

@Schema(description = "One piece of evidence an answer cites, with the values exactly as the source system holds them.")
public record EvidenceDto(
        @Schema(example = "attendance:2026-10-05:project-42") String id,
        @Schema(description = "The provider that supplied it.", example = "attendance") String providerId,
        @Schema(example = "attendance-day-summary") String kind,
        @Schema(description = "Field name to value, with the units each provider's description gives.") Map<String, Object> values,
        @Schema(description = "Where to open it: a record, or a place in a document.") SourceRef source
) {}
