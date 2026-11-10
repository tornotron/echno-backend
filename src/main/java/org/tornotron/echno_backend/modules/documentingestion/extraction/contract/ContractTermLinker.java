package org.tornotron.echno_backend.modules.documentingestion.extraction.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.Segment;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SegmentRepository;

/**
 * Links the package's contract terms across its general conditions, special conditions and contract
 * data, through {@link TermOverrides}, whenever a reading of one of them ends. The documents are read by
 * separate jobs in any order, so the whole package is looked at each time. Writes candidate rows only.
 */
@Component
public class ContractTermLinker {

    /** The most contract term candidates of one package read; 19 terms over many chunks fit well within. */
    static final int MAX_TERMS = 5_000;

    private final CandidateRepository candidates;
    private final SegmentRepository segments;

    public ContractTermLinker(CandidateRepository candidates, SegmentRepository segments) {
        this.candidates = candidates;
        this.segments = segments;
    }

    @Transactional
    public void linkPackage(UUID packageId) {
        List<Candidate> rows = candidates.findForReview(packageId, CandidateKind.CONTRACT_TERM, null, null,
                PageRequest.of(0, MAX_TERMS)).getContent();
        if (rows.isEmpty()) {
            return;
        }
        List<UUID> segmentIds = rows.stream().map(Candidate::getSegmentId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, DocClass> classes = (segmentIds.isEmpty() ? List.<Segment>of() : segments.findByIdsScoped(segmentIds))
                .stream().collect(Collectors.toMap(Segment::getId,
                        s -> DocClass.parse(s.getDocClass()).orElse(DocClass.OTHER)));
        Map<UUID, Candidate> byId = rows.stream().collect(Collectors.toMap(Candidate::getId, Function.identity()));
        List<TermOverrides.Row> view = rows.stream().map(c -> new TermOverrides.Row(c.getId(), c.getNaturalKey(),
                classes.getOrDefault(c.getSegmentId(), DocClass.OTHER), c.getStatus(),
                c.getPayload().get(ContractTerm.CLAUSE) == null ? null : c.getPayload().get(ContractTerm.CLAUSE).toString(),
                c.getFlags(), c.getSupersedesCandidateId(), c.getCreatedAt())).toList();
        TermOverrides.decide(view).forEach((id, change) -> {
            Candidate c = byId.get(id);
            if (change.addFlag() != null) {
                List<CandidateFlag> flags = new ArrayList<>(c.getFlags());
                flags.add(change.addFlag());
                c.setFlags(flags);
            }
            if (change.supersedes() != null) {
                c.setSupersedesCandidateId(change.supersedes());
            }
            candidates.save(c);
        });
    }
}
