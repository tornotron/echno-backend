package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractionMethod;

/** A candidate as the review screen shows it, with every source beside it. */
public record CandidateDto(UUID id, UUID packageId, UUID documentId, UUID segmentId, CandidateKind kind,
                           String naturalKey, Map<String, Object> payload, Map<String, Object> acceptedPayload,
                           List<CandidateFlag> flags, List<String> acknowledgedFlags, ExtractionMethod method,
                           CandidateStatus status, UUID supersedesCandidateId, Long reviewedBy,
                           LocalDateTime reviewedAt, String appliedTargetType, String appliedTargetId,
                           String applyError,
                           @Schema(description = "When a change was refused because its record was edited by hand "
                                   + "after it was applied: the value on the record at that apply", nullable = true)
                           String conflictRecordValue,
                           @Schema(description = "When a change was refused because its record was edited by hand "
                                   + "after it was applied: the value last applied to the record", nullable = true)
                           String conflictAppliedValue,
                           List<SourceRefDto> sources) {

    public static CandidateDto of(Candidate c, List<SourceRefDto> sources) {
        return new CandidateDto(c.getId(), c.getPackageId(), c.getDocumentId(), c.getSegmentId(), c.getKind(),
                c.getNaturalKey(), c.getPayload(), c.getAcceptedPayload(), c.getFlags(), c.getAcknowledgedFlags(),
                c.getMethod(), c.getStatus(), c.getSupersedesCandidateId(), c.getReviewedBy(), c.getReviewedAt(),
                c.getAppliedTargetType(), c.getAppliedTargetId(), c.getApplyError(), c.getConflictRecordValue(),
                c.getConflictAppliedValue(), sources);
    }
}
