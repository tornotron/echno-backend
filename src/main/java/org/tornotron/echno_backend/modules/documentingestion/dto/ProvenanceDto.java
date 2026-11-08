package org.tornotron.echno_backend.modules.documentingestion.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.Provenance;

/** Where one field of a live record came from. {@code source} is null when it was entered by hand. */
public record ProvenanceDto(UUID id, String targetType, String targetId, String targetField, String valueExtracted,
                            String valueAccepted, boolean enteredByHand, UUID candidateId, Long acceptedBy,
                            LocalDateTime acceptedAt, Long appliedBy, LocalDateTime appliedAt, SourceRefDto source) {

    public static ProvenanceDto of(Provenance p, SourceRefDto source) {
        return new ProvenanceDto(p.getId(), p.getTargetType(), p.getTargetId(), p.getTargetField(),
                p.getValueExtracted(), p.getValueAccepted(), p.isEnteredByHand(), p.getCandidateId(),
                p.getAcceptedBy(), p.getAcceptedAt(), p.getAppliedBy(), p.getAppliedAt(), source);
    }
}
