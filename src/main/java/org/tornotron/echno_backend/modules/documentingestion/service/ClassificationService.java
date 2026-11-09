package org.tornotron.echno_backend.modules.documentingestion.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.documentingestion.domain.ClassMethod;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJob;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.Segment;
import org.tornotron.echno_backend.modules.documentingestion.domain.SegmentStatus;
import org.tornotron.echno_backend.modules.documentingestion.dto.ClassificationRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.ConfirmAllResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentClassificationDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ExtractJobDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.SegmentDto;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.classify.ClassificationExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.Extractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractorRegistry;
import org.tornotron.echno_backend.modules.documentingestion.job.ExtractJobQueue;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ExtractJobRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestDocumentRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SegmentRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Class confirmation: a person confirms each document's class and its page segments, one by one or all
 * at once, before any segment is read further. Confirming queues the readers of each segment's class.
 * Redrawing segments is refused once any value read from them is on a live record.
 */
@Service
public class ClassificationService {

    static final int MAX_PAGE_SIZE = 100;
    static final int CONFIRM_ALL_BATCH = 100;
    static final String REDRAWN = "The segment was redrawn, so this reading was stopped.";

    private final IngestDocumentRepository documents;
    private final IngestPackageRepository packages;
    private final SegmentRepository segments;
    private final CandidateRepository candidates;
    private final ExtractJobRepository jobs;
    private final CandidateWriter writer;
    private final ExtractorRegistry registry;
    private final ExtractJobQueue queue;
    private final TransactionRetryTemplate retry;
    private final UserContextService userContextService;

    public ClassificationService(IngestDocumentRepository documents, IngestPackageRepository packages,
                                 SegmentRepository segments, CandidateRepository candidates, ExtractJobRepository jobs,
                                 CandidateWriter writer, ExtractorRegistry registry, ExtractJobQueue queue,
                                 TransactionRetryTemplate retry, UserContextService userContextService) {
        this.documents = documents;
        this.packages = packages;
        this.segments = segments;
        this.candidates = candidates;
        this.jobs = jobs;
        this.writer = writer;
        this.registry = registry;
        this.queue = queue;
        this.retry = retry;
        this.userContextService = userContextService;
    }

    public Page<DocumentClassificationDto> list(UUID packageId, int page, int size) {
        return retry.execute("DocumentIngestion.classificationList", () -> {
            requirePackage(packageId);
            Page<IngestDocument> docs = documents.findByPackage(packageId,
                    PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
            List<UUID> ids = docs.map(IngestDocument::getId).getContent();
            Map<UUID, List<SegmentDto>> byDoc = ids.isEmpty() ? Map.of() : segments.findPageSegments(ids).stream()
                    .collect(Collectors.groupingBy(Segment::getDocumentId, LinkedHashMap::new,
                            Collectors.mapping(SegmentDto::of, Collectors.toList())));
            return docs.map(d -> DocumentClassificationDto.of(d, byDoc.getOrDefault(d.getId(), List.of()),
                    jobs.findLatest(d.getId(), ClassificationExtractor.KEY, PageRequest.of(0, 1)).stream().findFirst()
                            .map(ExtractJobDto::of).orElse(null)));
        });
    }

    /** Confirms a document's class and segments as given, replacing what was proposed. */
    public DocumentClassificationDto confirm(UUID documentId, ClassificationRequest req) {
        Long userId = userContextService.getCurrentUserId();
        retry.execute("DocumentIngestion.classificationConfirm", () -> {
            IngestDocument doc = requireOpenDocument(documentId);
            confirmOne(doc, req.docClass(), ranges(doc, req), userId, LocalDateTime.now());
            return null;
        });
        return retry.execute("DocumentIngestion.classificationRead", () -> {
            IngestDocument doc = documents.findByIdScoped(documentId).orElseThrow();
            return DocumentClassificationDto.of(doc, segments.findPageSegments(documentId).stream().map(SegmentDto::of)
                    .toList(), jobs.findLatest(documentId, ClassificationExtractor.KEY, PageRequest.of(0, 1)).stream()
                    .findFirst().map(ExtractJobDto::of).orElse(null));
        });
    }

    /** Confirms every proposed class and segment of a package as proposed. */
    public ConfirmAllResultDto confirmAll(UUID packageId) {
        Long userId = userContextService.getCurrentUserId();
        return retry.execute("DocumentIngestion.classificationConfirmAll", () -> {
            requirePackage(packageId);
            requireOpen(packageId);
            int docs = 0;
            int segs = 0;
            int queued = 0;
            LocalDateTime now = LocalDateTime.now();
            for (IngestDocument doc : documents.findUnconfirmed(packageId, PageRequest.of(0, CONFIRM_ALL_BATCH))) {
                DocClass docClass = DocClass.parse(doc.getDocClass()).orElse(DocClass.OTHER);
                List<ClassificationRequest.Range> ranges = segments.findPageSegments(doc.getId()).stream()
                        .filter(s -> s.getPageFrom() != null)
                        .map(s -> new ClassificationRequest.Range(DocClass.parse(s.getDocClass()).orElse(DocClass.OTHER),
                                s.getPageFrom(), s.getPageTo()))
                        .toList();
                int[] counts = confirmOne(doc, docClass, ranges, userId, now);
                docs++;
                segs += counts[0];
                queued += counts[1];
            }
            int remaining = documents.findUnconfirmed(packageId, PageRequest.of(0, 1)).size();
            return new ConfirmAllResultDto(docs, segs, queued, remaining);
        });
    }

    /** @return segments confirmed, and jobs queued */
    private int[] confirmOne(IngestDocument doc, DocClass docClass, List<ClassificationRequest.Range> ranges,
                             Long userId, LocalDateTime now) {
        List<Segment> existing = segments.findPageSegments(doc.getId());
        List<Segment> kept = new ArrayList<>();
        List<Segment> removed = new ArrayList<>();
        for (Segment s : existing) {
            boolean same = ranges.stream().anyMatch(r -> r.docClass().name().equals(s.getDocClass())
                    && r.pageFrom().equals(s.getPageFrom()) && r.pageTo().equals(s.getPageTo()));
            (same ? kept : removed).add(s);
        }
        for (Segment s : removed) {
            if (candidates.countAppliedInSegment(s.getId()) > 0) {
                throw new InvalidRequestException("Values read from pages " + s.getPageFrom() + " to " + s.getPageTo()
                        + " are already on live records, so that segment cannot be redrawn");
            }
        }
        if (!removed.isEmpty()) {
            for (ExtractJob job : jobs.findUnfinishedForSegments(removed.stream().map(Segment::getId).toList(),
                    PageRequest.of(0, 200))) {
                if (job.getStatus() == ExtractJobStatus.RUNNING) {
                    throw new InvalidRequestException("A segment of " + doc.getFileName()
                            + " is being read now; wait for it to finish before redrawing");
                }
                job.setStatus(ExtractJobStatus.FAILED);
                job.setMessage(REDRAWN);
                job.setFinishedAt(now);
                jobs.save(job);
            }
            removed.forEach(s -> writer.discardUnapplied(s.getId()));
            segments.deleteAll(removed);
            segments.flush();
        }
        int queued = 0;
        List<Segment> confirmed = new ArrayList<>();
        for (ClassificationRequest.Range r : ranges) {
            Segment s = kept.stream().filter(k -> r.docClass().name().equals(k.getDocClass())
                    && r.pageFrom().equals(k.getPageFrom()) && r.pageTo().equals(k.getPageTo())).findFirst().orElse(null);
            if (s == null) {
                s = new Segment();
                s.setOrganization(doc.getOrganization());
                s.setDocumentId(doc.getId());
                s.setDocClass(r.docClass().name());
                s.setPageFrom(r.pageFrom());
                s.setPageTo(r.pageTo());
                s.setMethod(ClassMethod.PERSON);
                s.setReason("Set by hand.");
            }
            if (!s.isConfirmed()) {
                s.setStatus(SegmentStatus.CONFIRMED);
                s.setConfirmedBy(userId);
                s.setConfirmedAt(now);
            }
            confirmed.add(segments.saveAndFlush(s));
        }
        if (!docClass.name().equals(doc.getDocClass())) {
            doc.setClassMethod(ClassMethod.PERSON);
            doc.setClassReason("Set by hand.");
        }
        doc.setDocClass(docClass.name());
        doc.setClassSetBy(userId);
        doc.setClassConfirmedAt(now);
        documents.save(doc);
        for (Segment s : confirmed) {
            for (Extractor e : registry.segmentExtractors(DocClass.parse(s.getDocClass()).orElse(DocClass.OTHER))) {
                queue.queue(doc.getOrganization(), doc.getPackageId(), doc.getId(), s.getId(), e, userId);
                queued++;
            }
        }
        if (isWorkbook(doc)) {
            // Only a reader of table rows can read a workbook; a page reader, such as project setup, would get no
            // pages and end as if it had read the file.
            queued += confirmWholeFile(doc, docClass, docClass == DocClass.BOQ ? List.of()
                    : registry.segmentExtractors(docClass).stream().filter(Extractor::readsTables).toList(),
                    "The whole workbook.", userId, now);
        } else if (isDrawingFile(doc)) {
            queued += confirmWholeFile(doc, docClass, registry.segmentExtractors(docClass).stream()
                    .filter(Extractor::readsTitleBlocks).toList(), "The whole drawing file.", userId, now);
        }
        return new int[] {confirmed.size(), queued};
    }

    /**
     * A file with no pages to split gets one confirmed segment for the whole file when its class has
     * readers for it, and those readers are queued on it: a workbook of a class read by extractors of
     * table rows, such as a milestone schedule, or a DXF or DWG confirmed as a drawing. A class read only
     * from pages, such as a NIT, is not read from a workbook. A BOQ workbook keeps its per-sheet flow,
     * whose segments are made when a sheet's mapping is confirmed. Changing the class drops the old
     * whole-file segment unless a value read from it is already on a live record.
     *
     * @return jobs queued
     */
    private int confirmWholeFile(IngestDocument doc, DocClass docClass, List<Extractor> readers, String reason,
                                 Long userId, LocalDateTime now) {
        Segment kept = null;
        for (Segment s : segments.findWholeWorkbook(doc.getId())) {
            if (kept == null && !readers.isEmpty() && docClass.name().equals(s.getDocClass())) {
                kept = s;
                continue;
            }
            if (candidates.countAppliedInSegment(s.getId()) > 0) {
                throw new InvalidRequestException("Values read from " + doc.getFileName()
                        + " are already on live records, so its class cannot be changed");
            }
            for (ExtractJob job : jobs.findUnfinishedForSegments(List.of(s.getId()), PageRequest.of(0, 200))) {
                if (job.getStatus() == ExtractJobStatus.RUNNING) {
                    throw new InvalidRequestException(doc.getFileName() + " is being read now; wait for it to finish "
                            + "before changing its class");
                }
                job.setStatus(ExtractJobStatus.FAILED);
                job.setMessage(REDRAWN);
                job.setFinishedAt(now);
                jobs.save(job);
            }
            writer.discardUnapplied(s.getId());
            segments.delete(s);
            segments.flush();
        }
        if (readers.isEmpty() || (kept != null && kept.isConfirmed())) {
            return 0;
        }
        Segment s = kept != null ? kept : new Segment();
        s.setOrganization(doc.getOrganization());
        s.setDocumentId(doc.getId());
        s.setDocClass(docClass.name());
        s.setSheetName(Segment.WHOLE_WORKBOOK);
        s.setMethod(ClassMethod.PERSON);
        s.setReason(reason);
        s.setStatus(SegmentStatus.CONFIRMED);
        s.setConfirmedBy(userId);
        s.setConfirmedAt(now);
        Segment saved = segments.saveAndFlush(s);
        for (Extractor e : readers) {
            queue.queue(doc.getOrganization(), doc.getPackageId(), doc.getId(), saved.getId(), e, userId);
        }
        return readers.size();
    }

    private static boolean isWorkbook(IngestDocument doc) {
        return doc.getFileType() == DocumentFileType.XLSX || doc.getFileType() == DocumentFileType.XLS;
    }

    private static boolean isDrawingFile(IngestDocument doc) {
        return doc.getFileType() == DocumentFileType.DXF || doc.getFileType() == DocumentFileType.DWG;
    }

    /** The ranges to confirm: as given, or the whole file for a PDF given none; none for a workbook. */
    private static List<ClassificationRequest.Range> ranges(IngestDocument doc, ClassificationRequest req) {
        if (doc.getFileType() != DocumentFileType.PDF) {
            return List.of();
        }
        Integer pages = doc.getPageCount();
        if (pages == null || pages < 1) {
            throw new InvalidRequestException(doc.getFileName() + " has no pages to split");
        }
        List<ClassificationRequest.Range> ranges = req.segments() == null || req.segments().isEmpty()
                ? List.of(new ClassificationRequest.Range(req.docClass(), 1, pages)) : req.segments();
        int last = 0;
        for (ClassificationRequest.Range r : ranges) {
            if (r.pageFrom() > r.pageTo()) {
                throw new InvalidRequestException("A segment starts on page " + r.pageFrom() + " after it ends");
            }
            if (r.pageTo() > pages) {
                throw new InvalidRequestException(doc.getFileName() + " has " + pages + " pages");
            }
            if (r.pageFrom() <= last) {
                throw new InvalidRequestException("Segments must be in page order and must not overlap");
            }
            last = r.pageTo();
        }
        return ranges;
    }

    private IngestDocument requireOpenDocument(UUID documentId) {
        IngestDocument doc = documents.findByIdScoped(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("No document " + documentId));
        requireOpen(doc.getPackageId());
        return doc;
    }

    private void requireOpen(UUID packageId) {
        if (packages.findByIdScoped(packageId).map(p -> p.getStatus() != PackageStatus.OPEN).orElse(true)) {
            throw new InvalidRequestException("The package is archived");
        }
    }

    private void requirePackage(UUID packageId) {
        if (packages.findByIdScoped(packageId).isEmpty()) {
            throw new ResourceNotFoundException("No document package " + packageId);
        }
    }
}
