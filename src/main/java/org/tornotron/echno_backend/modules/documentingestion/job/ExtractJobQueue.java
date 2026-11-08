package org.tornotron.echno_backend.modules.documentingestion.job;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJob;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.Extractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.IngestLlmProperties;
import org.tornotron.echno_backend.modules.documentingestion.repository.ExtractJobRepository;

/**
 * Puts a job on the extract queue, inside the caller's transaction. A job of the same extractor that is
 * still open for the same document and segment is returned instead, so confirming twice queues one.
 */
@Component
public class ExtractJobQueue {

    private final ExtractJobRepository jobs;
    private final IngestLlmProperties properties;

    public ExtractJobQueue(ExtractJobRepository jobs, IngestLlmProperties properties) {
        this.jobs = jobs;
        this.properties = properties;
    }

    public ExtractJob queue(Organization organization, UUID packageId, UUID documentId, UUID segmentId,
                            Extractor extractor, Long createdBy) {
        var open = jobs.findOpen(documentId, segmentId, extractor.key(), PageRequest.of(0, 1));
        if (!open.isEmpty()) {
            return open.get(0);
        }
        ExtractJob job = new ExtractJob();
        job.setOrganization(organization);
        job.setPackageId(packageId);
        job.setDocumentId(documentId);
        job.setSegmentId(segmentId);
        job.setExtractorKey(extractor.key());
        job.setExtractorVersion(extractor.version());
        job.setStatus(ExtractJobStatus.QUEUED);
        job.setMaxAttempts(Math.max(1, properties.getMaxAttempts()));
        job.setQueuedAt(LocalDateTime.now());
        job.setCreatedBy(createdBy);
        return jobs.save(job);
    }
}
