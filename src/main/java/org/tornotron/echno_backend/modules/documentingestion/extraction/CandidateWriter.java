package org.tornotron.echno_backend.modules.documentingestion.extraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Proposes a batch in one transaction; the caller keeps batches small. */
    @Transactional
    public List<UUID> proposeAll(List<ProposedCandidate> batch) {
        List<UUID> ids = new ArrayList<>(batch.size());
        for (ProposedCandidate proposed : batch) {
            ids.add(propose(proposed));
        }
        return ids;
    }

    /**
     * Removes what a segment proposed and nobody applied, with its sources, so reading the segment
     * again replaces its rows instead of adding to them. Applied rows stay, as provenance points at them.
     *
     * @return how many candidates were removed
     */
    @Transactional
    public int discardUnapplied(UUID segmentId) {
        Long orgId = tenantEntityHelper.resolveCurrentOrganization().getId();
        sources.deleteForUnappliedInSegment(orgId, segmentId);
        return candidates.deleteUnappliedInSegment(orgId, segmentId);
    }

    /**
     * Proposes a value an LLM extractor read from one chunk, or refreshes the one it proposed before for
     * the same job, chunk and natural key, so a retried chunk never adds a second row. A row a person
     * has already decided on is left as it is.
     */
    @Transactional
    public UUID upsertFromChunk(UUID jobId, String chunkKey, ProposedCandidate proposed) {
        Candidate existing = candidates.findFromChunk(jobId, chunkKey, proposed.kind(), proposed.naturalKey())
                .orElse(null);
        if (existing == null) {
            return write(new Candidate(), jobId, chunkKey, proposed);
        }
        if (existing.getStatus() != CandidateStatus.PROPOSED) {
            return existing.getId();
        }
        sources.deleteForCandidate(existing.getOrganization().getId(), existing.getId());
        return write(existing, jobId, chunkKey, proposed);
    }

    @Transactional
    public UUID propose(ProposedCandidate proposed) {
        return write(new Candidate(), null, null, proposed);
    }

    private UUID write(Candidate c, UUID jobId, String chunkKey, ProposedCandidate proposed) {
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
        c.setExtractJobId(jobId);
        c.setChunkKey(chunkKey);
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
