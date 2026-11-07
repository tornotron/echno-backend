package org.tornotron.echno_backend.modules.documentingestion.dto;

import java.util.UUID;

/** What became of one decision. A bulk accept reports each row rather than failing as a whole. */
public record DecisionOutcomeDto(UUID candidateId, Outcome outcome, String message) {

    public enum Outcome {
        ACCEPTED, REJECTED, SKIPPED_FLAGGED, REFUSED_NO_SOURCE, REFUSED_STATE, REFUSED_ROLE, NOT_FOUND
    }
}
