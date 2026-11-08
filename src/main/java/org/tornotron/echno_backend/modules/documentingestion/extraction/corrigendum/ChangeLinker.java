package org.tornotron.echno_backend.modules.documentingestion.extraction.corrigendum;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStage;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;

/**
 * Links proposed changes to the earlier candidates they change, through {@link ChangeMatcher}, and writes
 * the link, the worked-out variance and impact, and the flags onto the change rows. Writes change rows only.
 */
@Component
public class ChangeLinker {

    /** The most earlier candidates of one kind read to match against; a package's BOQ fits well within. */
    static final int MAX_PRIORS = 20_000;

    private final CandidateRepository candidates;
    private final IngestPackageRepository packages;

    public ChangeLinker(CandidateRepository candidates, IngestPackageRepository packages) {
        this.candidates = candidates;
        this.packages = packages;
    }

    /** Links every change a job proposed. */
    @Transactional
    public void linkJob(UUID jobId, UUID packageId) {
        link(candidates.findProposedChangesOfJob(jobId), packageId);
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
                    .ifPresent(k -> priors.computeIfAbsent(k, kind -> candidates.findForReview(packageId, kind, null, null,
                            PageRequest.of(0, MAX_PRIORS)).getContent()));
            List<Candidate> pool = new ArrayList<>();
            priors.values().forEach(pool::addAll);
            ChangeMatcher.Match match = ChangeMatcher.match(change.getPayload(), change.getDocumentId(), pool, tender);
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
}
