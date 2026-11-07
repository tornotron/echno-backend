package org.tornotron.echno_backend.modules.documentingestion.extraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.SourceRef;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SourceRefRepository;

/**
 * The one way an extractor writes: into candidate and source reference rows of the current tenant,
 * and nowhere else. {@code DocumentIngestionWriteBoundaryTest} keeps this package away from core
 * services and other modules' ports.
 */
@Service
@RequiredArgsConstructor
public class CandidateWriter {

    private final CandidateRepository candidates;
    private final SourceRefRepository sources;
    private final TenantEntityHelper tenantEntityHelper;

    @Transactional
    public UUID propose(ProposedCandidate proposed) {
        Candidate c = new Candidate();
        c.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        c.setPackageId(proposed.packageId());
        c.setDocumentId(proposed.documentId());
        c.setSegmentId(proposed.segmentId());
        c.setKind(proposed.kind());
        c.setNaturalKey(proposed.naturalKey());
        c.setPayload(new LinkedHashMap<>(proposed.payload()));
        c.setFlags(proposed.flags() == null ? new ArrayList<>() : new ArrayList<>(proposed.flags()));
        c.setMethod(proposed.method());
        c.setStatus(CandidateStatus.PROPOSED);
        c.setSupersedesCandidateId(proposed.supersedesCandidateId());
        c = candidates.save(c);
        for (ProposedCandidate.ProposedSource s : proposed.sources() == null
                ? java.util.List.<ProposedCandidate.ProposedSource>of() : proposed.sources()) {
            SourceRef ref = new SourceRef();
            ref.setOrganization(c.getOrganization());
            ref.setCandidateId(c.getId());
            ref.setField(s.field());
            ref.setDocumentId(s.documentId());
            ref.setDocumentSha256(s.documentSha256());
            ref.setPage(s.page());
            ref.setBox(s.box());
            ref.setSheet(s.sheet());
            ref.setRowNumber(s.rowNumber());
            ref.setCell(s.cell());
            ref.setParagraph(s.paragraph());
            ref.setQuote(s.quote());
            ref.setQuoteVerified(s.quoteVerified());
            sources.save(ref);
        }
        return c.getId();
    }
}
