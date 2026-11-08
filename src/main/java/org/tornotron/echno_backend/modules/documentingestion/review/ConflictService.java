package org.tornotron.echno_backend.modules.documentingestion.review;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.documentingestion.apply.ChangeApplier;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ConflictChoiceRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.SourceRefDto;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SourceRefRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * A person's choice when a change could not be applied because its target was edited by hand since the
 * value was applied (design section 4.8): the apply step refused it and named all three values, and nothing
 * was overwritten. Applying the change marks it so the next apply writes it over the record, as long as the
 * record still holds the value it held when the person chose; keeping the record rejects the change and puts
 * the earlier value back as the one in force, unless another change to it still stands. Either way the choice
 * is recorded by name.
 */
@Service
public class ConflictService {

    /** A change in one of these states still stands over the row it supersedes. */
    private static final java.util.Set<CandidateStatus> STANDING = EnumSet.of(CandidateStatus.ACCEPTED,
            CandidateStatus.APPLIED, CandidateStatus.APPLY_FAILED);

    private final CandidateRepository candidates;
    private final SourceRefRepository sources;
    private final TransactionRetryTemplate retry;
    private final UserContextService userContextService;
    private final ChangeApplier changes;

    public ConflictService(CandidateRepository candidates, SourceRefRepository sources, TransactionRetryTemplate retry,
                           UserContextService userContextService, ChangeApplier changes) {
        this.candidates = candidates;
        this.sources = sources;
        this.retry = retry;
        this.userContextService = userContextService;
        this.changes = changes;
    }

    public CandidateDto choose(UUID candidateId, ConflictChoiceRequest req) {
        Long reviewer = userContextService.getCurrentUserId();
        return retry.execute("DocumentIngestion.conflictChoice", () -> {
            Candidate c = candidates.findByIdScoped(candidateId)
                    .orElseThrow(() -> new ResourceNotFoundException("No candidate " + candidateId));
            if (c.getKind() != CandidateKind.CHANGE || c.getStatus() != CandidateStatus.APPLY_FAILED) {
                throw new InvalidRequestException("Only a change that could not be applied has a choice to make");
            }
            LocalDateTime now = LocalDateTime.now();
            if (req.choice() == ConflictChoiceRequest.Choice.APPLY_CHANGE) {
                Map<String, Object> accepted = new LinkedHashMap<>(c.getAcceptedPayload() == null ? c.getPayload()
                        : c.getAcceptedPayload());
                accepted.put(ChangeApplier.OVERRIDE_RECORD, true);
                String onRecord = changes.recordValue(c);
                if (onRecord == null) {
                    accepted.remove(ChangeApplier.OVERRIDE_RECORD_VALUE);
                } else {
                    accepted.put(ChangeApplier.OVERRIDE_RECORD_VALUE, onRecord);
                }
                c.setAcceptedPayload(accepted);
                c.setStatus(CandidateStatus.ACCEPTED);
            } else {
                c.setStatus(CandidateStatus.REJECTED);
                if (c.getSupersedesCandidateId() != null) {
                    candidates.findByIdScoped(c.getSupersedesCandidateId())
                            .filter(prior -> prior.getStatus() == CandidateStatus.SUPERSEDED)
                            .filter(prior -> candidates.findSuccessors(prior.getId(), CandidateKind.CHANGE).stream()
                                    .noneMatch(other -> !other.getId().equals(c.getId())
                                            && STANDING.contains(other.getStatus())))
                            .ifPresent(prior -> {
                                prior.setStatus(prior.getAppliedAt() != null ? CandidateStatus.APPLIED
                                        : prior.getReviewedAt() != null ? CandidateStatus.ACCEPTED : CandidateStatus.PROPOSED);
                                candidates.save(prior);
                            });
                }
            }
            c.setReviewedBy(reviewer);
            c.setReviewedAt(now);
            Candidate saved = candidates.save(c);
            return CandidateDto.of(saved, sources.findForCandidates(java.util.List.of(saved.getId())).stream()
                    .map(SourceRefDto::of).toList());
        });
    }
}
