package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "One decision on one candidate. EDIT accepts the candidate with the given fields changed.")
public record CandidateDecision(
        @NotNull UUID candidateId,
        @NotNull Action action,
        @Schema(description = "Fields to change, for EDIT") Map<String, Object> edits,
        @Schema(description = "Codes of the flags the reviewer has read; a flagged candidate needs every one") List<String> acknowledgedFlags
) {

    public enum Action {
        ACCEPT, REJECT, EDIT
    }
}
