package org.tornotron.echno_backend.modules.documentingestion.review;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDecision;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DecisionOutcomeDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DecisionOutcomeDto.Outcome;
import org.tornotron.echno_backend.modules.documentingestion.dto.SourceRefDto;
import org.tornotron.echno_backend.modules.documentingestion.extraction.quote.QuotedCandidates;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SourceRefRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Review, generic over kinds. Accepting needs a source reference: a value with nowhere to point is
 * never accepted. A flagged candidate is skipped by a bulk accept unless each of its flags is
 * acknowledged, and one whose quote was not found is accepted only on its own. A correction keeps the extracted value beside the accepted one. Accepting a revised
 * value supersedes the one it replaces. Each decision is recorded with who made it and when; a person
 * may review what they uploaded.
 */
@Service
public class ReviewService {

    static final int MAX_PAGE_SIZE = 200;

    /** Flags a reviewer can acknowledge only in a request about that one candidate (design section 8.3). */
    static final Set<String> SINGLE_ROW_FLAGS = Set.of(QuotedCandidates.QUOTE_NOT_FOUND);

    private final CandidateRepository candidates;
    private final SourceRefRepository sources;
    private final IngestPackageRepository packages;
    private final ReviewAccess reviewAccess;
    private final UserContextService userContextService;

    public ReviewService(CandidateRepository candidates, SourceRefRepository sources, IngestPackageRepository packages,
                         ReviewAccess reviewAccess, UserContextService userContextService) {
        this.candidates = candidates;
        this.sources = sources;
        this.packages = packages;
        this.reviewAccess = reviewAccess;
        this.userContextService = userContextService;
    }

    @Transactional(readOnly = true)
    public Page<CandidateDto> list(UUID packageId, CandidateKind kind, CandidateStatus status, UUID segmentId,
                                   int page, int size) {
        requirePackage(packageId);
        Page<Candidate> rows = candidates.findForReview(packageId, kind, status, segmentId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
        Map<UUID, List<SourceRefDto>> refs = sources.findForCandidates(rows.map(Candidate::getId).getContent()).stream()
                .collect(Collectors.groupingBy(s -> s.getCandidateId(), LinkedHashMap::new,
                        Collectors.mapping(SourceRefDto::of, Collectors.toList())));
        return rows.map(c -> CandidateDto.of(c, refs.getOrDefault(c.getId(), List.of())));
    }

    @Transactional
    public List<DecisionOutcomeDto> decide(UUID packageId, List<CandidateDecision> decisions) {
        requirePackage(packageId);
        Long reviewer = userContextService.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        List<DecisionOutcomeDto> outcomes = new ArrayList<>();
        for (CandidateDecision decision : decisions) {
            Candidate c = candidates.findByIdScoped(decision.candidateId())
                    .filter(found -> found.getPackageId().equals(packageId))
                    .orElse(null);
            if (c == null) {
                outcomes.add(new DecisionOutcomeDto(decision.candidateId(), Outcome.NOT_FOUND, "No such candidate in this package"));
                continue;
            }
            outcomes.add(decideOne(c, decision, decisions.size() > 1, reviewer, now));
        }
        return outcomes;
    }

    private DecisionOutcomeDto decideOne(Candidate c, CandidateDecision decision, boolean bulk, Long reviewer,
                                         LocalDateTime now) {
        if (!reviewAccess.mayReview(c.getKind())) {
            return new DecisionOutcomeDto(c.getId(), Outcome.REFUSED_ROLE, "Your role does not review " + c.getKind());
        }
        if (c.getStatus() == CandidateStatus.APPLIED || c.getStatus() == CandidateStatus.SUPERSEDED) {
            return new DecisionOutcomeDto(c.getId(), Outcome.REFUSED_STATE, "The candidate is already " + c.getStatus());
        }
        if (decision.action() == CandidateDecision.Action.REJECT) {
            c.setStatus(CandidateStatus.REJECTED);
            stamp(c, reviewer, now);
            return new DecisionOutcomeDto(c.getId(), Outcome.REJECTED, null);
        }
        if (sources.countForCandidate(c.getId()) == 0) {
            return new DecisionOutcomeDto(c.getId(), Outcome.REFUSED_NO_SOURCE,
                    "The candidate has no source reference, so it cannot be accepted");
        }
        if (bulk && c.getFlags().stream().anyMatch(f -> SINGLE_ROW_FLAGS.contains(f.code()))) {
            return new DecisionOutcomeDto(c.getId(), Outcome.SKIPPED_FLAGGED,
                    "Its quote was not found on the page it names, so it is accepted only on its own, never in bulk");
        }
        Set<String> acknowledged = decision.acknowledgedFlags() == null ? Set.of() : new HashSet<>(decision.acknowledgedFlags());
        List<String> unread = c.getFlags().stream().map(CandidateFlag::code).filter(code -> !acknowledged.contains(code)).toList();
        if (!unread.isEmpty()) {
            return new DecisionOutcomeDto(c.getId(), Outcome.SKIPPED_FLAGGED, "Flags not acknowledged: " + String.join(", ", unread));
        }
        Map<String, Object> accepted = new LinkedHashMap<>(c.getPayload());
        if (decision.action() == CandidateDecision.Action.EDIT && decision.edits() != null) {
            accepted.putAll(decision.edits());
        }
        c.setAcceptedPayload(accepted);
        c.setAcknowledgedFlags(new ArrayList<>(c.getFlags().stream().map(CandidateFlag::code).toList()));
        c.setApplyError(null);
        c.setStatus(CandidateStatus.ACCEPTED);
        stamp(c, reviewer, now);
        if (c.getSupersedesCandidateId() != null) {
            candidates.findByIdScoped(c.getSupersedesCandidateId())
                    .filter(prior -> prior.getStatus() != CandidateStatus.SUPERSEDED)
                    .ifPresent(prior -> {
                        prior.setStatus(CandidateStatus.SUPERSEDED);
                        candidates.save(prior);
                    });
        }
        candidates.save(c);
        return new DecisionOutcomeDto(c.getId(), Outcome.ACCEPTED, null);
    }

    private void stamp(Candidate c, Long reviewer, LocalDateTime now) {
        c.setReviewedBy(reviewer);
        c.setReviewedAt(now);
        candidates.save(c);
    }

    private void requirePackage(UUID packageId) {
        if (packages.findByIdScoped(packageId).isEmpty()) {
            throw new ResourceNotFoundException("No document package " + packageId);
        }
    }
}
