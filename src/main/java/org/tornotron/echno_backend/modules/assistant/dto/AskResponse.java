package org.tornotron.echno_backend.modules.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.assistant.domain.AskOutcome;

@Schema(description = "The assistant's reply: an answer with citations, a question back, a decline that says what was "
        + "looked for, an answer missing some sources, or notice that the model is unavailable.")
public record AskResponse(
        @Schema(description = "Id of the audit row for this question; pass it as previousQueryId to follow up.") UUID queryId,
        AskOutcome outcome,
        @Schema(description = "A sentence for the person: what was found, or why not.") String message,
        @Schema(description = "The answer, facts first then inferences and recommendations. Empty unless ANSWERED or DEGRADED.")
        List<StatementDto> statements,
        @Schema(description = "The evidence the statements cite.") List<EvidenceDto> evidence,
        @Schema(description = "What to ask the person back. Only for CLARIFY.") List<String> clarification,
        @Schema(description = "What was searched, one line per provider. For DECLINED and DEGRADED.") List<String> lookedFor,
        @Schema(description = "Defaults the assistant applied, for example the period it chose when none was given.") List<String> assumptions,
        @Schema(description = "Sources that could not be read. Only for DEGRADED.") List<String> unavailableSources,
        CostDto cost
) {}
