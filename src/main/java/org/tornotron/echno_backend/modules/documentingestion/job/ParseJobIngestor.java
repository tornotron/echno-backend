package org.tornotron.echno_backend.modules.documentingestion.job;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionStorageLayout;
import org.tornotron.echno_backend.modules.documentingestion.api.DocumentTextReady;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.Extractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractorRegistry;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJob;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJobStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJobType;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestDocumentRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ParseJobRepository;
import org.tornotron.echno_backend.modules.documentingestion.service.DocumentIngestionLimits;

/**
 * Reads a closed parse job back into its document, inside the job's own tenant. A FAILED job fails
 * its document with the worker's sentence. A DONE {@code PARSE} job marks the document parsed with
 * the worker's counts and where its outputs are. A DONE {@code UNPACK} job turns the ZIP's manifest
 * into one child document per member, each queued for its own parse.
 *
 * <p>A parsed document queues its classification on the extract queue and publishes
 * {@link DocumentTextReady}, which nothing listens to yet.
 *
 * <p>Every write is fenced on the ingest claim the poller took, so a replica whose claim was taken
 * over cannot commit over the one that took it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParseJobIngestor {

    static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final ParseJobRepository jobs;
    private final IngestDocumentRepository documents;
    private final FileStorageService fileStorageService;
    private final DocumentIngestionLimits limits;
    private final ObjectMapper objectMapper;
    private final IngestPackageRepository packages;
    private final ExtractorRegistry extractors;
    private final ExtractJobQueue extractQueue;
    private final ApplicationEventPublisher events;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Manifest(List<Member> members) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Member(String path, String fileName, String sha256, Long sizeBytes) {
    }

    @Transactional
    public void ingest(UUID jobId, String claimedBy) {
        ParseJob job = jobs.findByIdScoped(jobId).orElse(null);
        if (job == null || job.getIngestedAt() != null || !claimedBy.equals(job.getIngestClaimedBy())) {
            log.info("Parse job {} is no longer this replica's to ingest; skipping", jobId);
            return;
        }
        IngestDocument doc = documents.findByIdScoped(job.getDocumentId()).orElseThrow(
                () -> new IllegalStateException("Parse job " + jobId + " names a document that does not exist"));
        if (job.getStatus() == ParseJobStatus.FAILED) {
            fail(doc, job.getError() == null ? "The file could not be read, and the worker gave no reason." : job.getError());
        } else if (job.getJobType() == ParseJobType.UNPACK) {
            unpack(job, doc);
        } else {
            parsed(job, doc);
            textReady(job, doc);
        }
        job.setIngestedAt(LocalDateTime.now());
        jobs.save(job);
        documents.save(doc);
    }

    /** Fails the job's document when ingesting it threw; the job is closed so the poller moves on. */
    @Transactional
    public void markFailed(UUID jobId, String message) {
        jobs.findByIdScoped(jobId).ifPresent(job -> {
            documents.findByIdScoped(job.getDocumentId()).ifPresent(doc -> {
                fail(doc, message);
                documents.save(doc);
            });
            job.setIngestedAt(LocalDateTime.now());
            jobs.save(job);
        });
    }

    private void parsed(ParseJob job, IngestDocument doc) {
        String prefix = job.getReusedOutputPrefix() != null ? job.getReusedOutputPrefix() : job.getOutputPrefix();
        // A reused prefix may belong to another package, never to another organization.
        if (!prefix.startsWith("doc-ingest/" + job.getOrganization().getId() + "/")) {
            throw new IllegalStateException("The outputs of parse job " + job.getId() + " are outside its organization");
        }
        if (!fileStorageService.objectExists(prefix + DocumentIngestionStorageLayout.META_FILE)) {
            throw new IllegalStateException("The worker closed the job but wrote no " + DocumentIngestionStorageLayout.META_FILE);
        }
        doc.setOutputPrefix(prefix);
        doc.setSha256(job.getSourceSha256());
        doc.setPageCount(job.getPageCount());
        doc.setSheetCount(job.getSheetCount());
        doc.setRowCount(job.getRowCount());
        doc.setPagesOcr(job.getPagesOcr());
        doc.setPagesFailed(job.getPagesFailed());
        doc.setStatus(DocumentStatus.PARSED);
        doc.setError(null);
    }

    /**
     * Queues the document-level readers (classification) and says the text is ready. Classification
     * runs on the extract queue: its deterministic part needs no LLM, so an Excel BOQ and the document
     * list work with LLM reading off.
     */
    private void textReady(ParseJob job, IngestDocument doc) {
        for (Extractor extractor : extractors.documentExtractors()) {
            extractQueue.queue(doc.getOrganization(), doc.getPackageId(), doc.getId(), null, extractor, doc.getCreatedBy());
        }
        Long projectId = packages.findByIdScoped(doc.getPackageId()).map(p -> p.getProjectId()).orElse(null);
        events.publishEvent(new DocumentTextReady(doc.getId(), job.getOrganization().getId(), projectId,
                doc.getOutputPrefix()));
    }

    private void unpack(ParseJob job, IngestDocument zip) {
        Manifest manifest = readManifest(job);
        List<Member> members = manifest.members() == null ? List.of() : manifest.members();
        long room = limits.maxDocumentsPerPackage() - documents.countInPackage(zip.getPackageId());
        if (members.size() > room) {
            fail(zip, "The ZIP holds " + members.size() + " files and the package has room for " + Math.max(room, 0)
                    + ". Split it, or start a second package.");
            return;
        }
        Long orgId = job.getOrganization().getId();
        for (Member member : members) {
            if (member.sha256() == null || !SHA256.matcher(member.sha256()).matches()) {
                throw new IllegalStateException("The ZIP manifest names a member with no valid hash");
            }
            IngestDocument child = new IngestDocument();
            child.setOrganization(zip.getOrganization());
            child.setPackageId(zip.getPackageId());
            child.setParentDocumentId(zip.getId());
            child.setFileName(memberName(member));
            child.setSizeBytes(member.sizeBytes() == null ? 0 : Math.max(member.sizeBytes(), 0));
            child.setSourceKey(DocumentIngestionStorageLayout.memberKey(orgId, zip.getPackageId(), member.sha256()));
            child.setContentType("application/octet-stream");
            child.setCreatedBy(zip.getCreatedBy());
            child.setUpdatedBy(zip.getCreatedBy());
            DocumentFileType type = DocumentFileType.ofFileName(child.getFileName()).orElse(null);
            child.setFileType(type == null ? DocumentFileType.ZIP : type);
            child.setStatus(DocumentStatus.QUEUED);
            child = documents.saveAndFlush(child);
            child.setOutputPrefix(DocumentIngestionStorageLayout.outputPrefix(orgId, zip.getPackageId(), child.getId()));
            if (type == null) {
                fail(child, child.getFileName() + " is not a type Echno reads. It is kept in the package.");
            } else if (type == DocumentFileType.ZIP) {
                fail(child, "A ZIP inside a ZIP is not unpacked. Upload " + child.getFileName() + " on its own.");
            } else if (type == DocumentFileType.DWG) {
                child.setStatus(DocumentStatus.STORED_ONLY);
                child.setError("DWG files are stored and listed but not read. Add a PDF or DXF copy to have it read.");
            } else {
                queueParse(job, child);
            }
            documents.save(child);
        }
        zip.setSha256(job.getSourceSha256());
        zip.setStatus(DocumentStatus.PARSED);
        zip.setError(null);
    }

    private void queueParse(ParseJob unpack, IngestDocument child) {
        ParseJob parse = new ParseJob();
        parse.setOrganization(unpack.getOrganization());
        parse.setPackageId(child.getPackageId());
        parse.setDocumentId(child.getId());
        parse.setJobType(ParseJobType.PARSE);
        parse.setStatus(ParseJobStatus.QUEUED);
        parse.setSourceKey(child.getSourceKey());
        parse.setSourceFileName(child.getFileName());
        parse.setOutputPrefix(child.getOutputPrefix());
        parse.setQueuedAt(LocalDateTime.now());
        jobs.save(parse);
    }

    private Manifest readManifest(ParseJob job) {
        String key = job.getOutputPrefix() + DocumentIngestionStorageLayout.MANIFEST_FILE;
        byte[] content = fileStorageService.readObject(key, MAX_MANIFEST_BYTES)
                .orElseThrow(() -> new IllegalStateException("The worker wrote no readable " + key))
                .content();
        try {
            return objectMapper.readValue(content, Manifest.class);
        } catch (IOException e) {
            throw new IllegalStateException("The ZIP manifest could not be read: " + e.getMessage(), e);
        }
    }

    private static String memberName(Member member) {
        String raw = member.fileName() != null ? member.fileName() : member.path();
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) {
            name = "unnamed";
        }
        return name.length() > 300 ? name.substring(name.length() - 300) : name;
    }

    private static void fail(IngestDocument doc, String message) {
        doc.setStatus(DocumentStatus.FAILED);
        doc.setError(message);
    }
}
