package org.tornotron.echno_backend.modules.documentingestion.extraction.corrigendum;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStage;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;

/**
 * Links proposed changes to the earlier candidates they change, through {@link ChangeMatcher}, and writes
 * the link, the worked-out variance and impact, and the flags onto the change rows. Writes change rows only.
 */
@Component
public class ChangeLinker {

    /** How many earlier candidates are read at a time; every page is read before matching. */
    static final int PAGE = 5_000;

    private final CandidateRepository candidates;
    private final IngestPackageRepository packages;

    public ChangeLinker(CandidateRepository candidates, IngestPackageRepository packages) {
        this.candidates = candidates;
        this.packages = packages;
    }

    /**
     * Links every change a job proposed. Only rows nobody has decided on are linked, so a job read again
     * never moves the link of a change a person has already accepted or rejected.
     */
    @Transactional
    public void linkJob(UUID jobId, UUID packageId) {
        link(candidates.findProposedOfJob(jobId, CandidateKind.CHANGE), packageId);
    }

    /** Links these changes, all of one package. */
    @Transactional
    public void link(List<Candidate> changes, UUID packageId) {
        if (changes.isEmpty()) {
            return;
        }
        boolean tender = packages.findByIdScoped(packageId).map(p -> p.getStage() == PackageStage.TENDER).orElse(false);
        Map<CandidateKind, List<Candidate>> priors = new EnumMap<>(CandidateKind.class);
        for (Candidate change : changes) {
            ChangeMatcher.kind(change.getPayload().get(ChangeMatcher.TARGET_KIND), change.getPayload().get(ChangeMatcher.FIELD))
                    .ifPresent(k -> priors.computeIfAbsent(k, kind -> all(packageId, kind)));
            List<Candidate> pool = new ArrayList<>();
            priors.values().forEach(pool::addAll);
            ChangeMatcher.Match match = ChangeMatcher.match(change.getPayload(), change.getDocumentId(), pool, tender,
                    this::current);
            Map<String, Object> payload = new LinkedHashMap<>(change.getPayload());
            payload.putAll(match.additions());
            change.setPayload(payload);
            List<CandidateFlag> flags = new ArrayList<>(change.getFlags());
            for (CandidateFlag f : match.flags()) {
                if (flags.stream().noneMatch(existing -> existing.code().equals(f.code()))) {
                    flags.add(f);
                }
            }
            change.setFlags(flags);
            change.setSupersedesCandidateId(match.priorId());
            candidates.save(change);
        }
    }

    private List<Candidate> all(UUID packageId, CandidateKind kind) {
        List<Candidate> out = new ArrayList<>();
        Page<Candidate> page = candidates.findForReview(packageId, kind, null, null, PageRequest.of(0, PAGE));
        out.addAll(page.getContent());
        while (page.hasNext()) {
            page = candidates.findForReview(packageId, kind, null, null, page.nextPageable());
            out.addAll(page.getContent());
        }
        return out;
    }

    /**
     * What a prior holds now. A row on a live record that changes have been applied to holds what they wrote,
     * in the order they were applied, so the next change is compared with the value on the record.
     */
    private Map<String, Object> current(Candidate prior) {
        Map<String, Object> values = ChangeMatcher.values(prior);
        if (prior.getStatus() != CandidateStatus.SUPERSEDED || prior.getAppliedTargetId() == null) {
            return values;
        }
        Map<String, Object> out = new LinkedHashMap<>(values);
        candidates.findSuccessors(prior.getId(), CandidateKind.CHANGE).stream()
                .filter(c -> c.getStatus() == CandidateStatus.APPLIED)
                .sorted(java.util.Comparator.comparing(Candidate::getAppliedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .forEach(c -> {
                    Map<String, Object> applied = ChangeMatcher.values(c);
                    Object field = applied.get(ChangeMatcher.FIELD);
                    String key = ChangeMatcher.payloadField(prior.getKind(), field == null ? null : field.toString());
                    Object value = applied.get(ChangeMatcher.NEW_VALUE);
                    if (key != null && value != null) {
                        out.put(key, value);
                    }
                });
        return out;
    }
}
