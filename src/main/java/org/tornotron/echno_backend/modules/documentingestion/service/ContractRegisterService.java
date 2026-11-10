package org.tornotron.echno_backend.modules.documentingestion.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJob;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;
import org.tornotron.echno_backend.modules.documentingestion.domain.Segment;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;
import org.tornotron.echno_backend.modules.documentingestion.dto.ContractTermCoverageDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ContractTermCoverageDto.FoundValue;
import org.tornotron.echno_backend.modules.documentingestion.dto.ContractTermCoverageDto.ReadPart;
import org.tornotron.echno_backend.modules.documentingestion.dto.ContractTermCoverageDto.Status;
import org.tornotron.echno_backend.modules.documentingestion.dto.ContractTermCoverageDto.TermCoverage;
import org.tornotron.echno_backend.modules.documentingestion.dto.ContractTermEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ObligationEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.extraction.contract.ContractTerm;
import org.tornotron.echno_backend.modules.documentingestion.extraction.contract.ContractTermsExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.contract.TermOverrides;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ContractTermEntryRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ExtractJobRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestDocumentRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ObligationEntryRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SegmentRepository;

/**
 * Reads of the contract registers (design section 4.5): the accepted terms, the Contract Obligation
 * Register, and the coverage of the 19 terms, where each term not found says which pages were searched.
 */
@Service
@RequiredArgsConstructor
public class ContractRegisterService {

    /**
     * Shown with the terms and the obligations. The register holds what was found, and nothing here
     * reads meaning into a clause.
     */
    public static final String NOTICE = "Obligations found in the conditions of contract that were read, not a "
            + "complete list. Terms and obligations are shown as the documents print them; Echno does not interpret "
            + "clauses, and this is not legal advice.";

    static final int MAX_JOBS = 500;
    static final int MAX_TERMS = 5_000;
    static final int MAX_PAGE_SIZE = 200;

    private static final Set<ExtractJobStatus> FINISHED = Set.of(ExtractJobStatus.DONE, ExtractJobStatus.PARTIAL);

    private final IngestPackageRepository packages;
    private final ContractTermEntryRepository terms;
    private final ObligationEntryRepository obligations;
    private final CandidateRepository candidates;
    private final SegmentRepository segments;
    private final ExtractJobRepository jobs;
    private final IngestDocumentRepository documents;

    @Transactional(readOnly = true)
    public List<ContractTermEntryDto> terms(UUID packageId) {
        requirePackage(packageId);
        return terms.findByPackage(packageId).stream().map(ContractTermEntryDto::of).toList();
    }

    @Transactional(readOnly = true)
    public Page<ObligationEntryDto> obligations(UUID packageId, int page, int size) {
        requirePackage(packageId);
        return obligations.findByPackage(packageId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))).map(ObligationEntryDto::of);
    }

    @Transactional(readOnly = true)
    public ContractTermCoverageDto coverage(UUID packageId) {
        requirePackage(packageId);
        Map<UUID, ExtractJob> latestBySegment = new LinkedHashMap<>();
        for (ExtractJob j : jobs.findByPackageAndKey(packageId, ContractTermsExtractor.KEY, PageRequest.of(0, MAX_JOBS))) {
            if (j.getSegmentId() != null) {
                latestBySegment.putIfAbsent(j.getSegmentId(), j);
            }
        }
        List<Candidate> rows = candidates.findForReview(packageId, CandidateKind.CONTRACT_TERM, null, null,
                PageRequest.of(0, MAX_TERMS)).getContent();
        Set<UUID> segmentIds = new TreeSet<>(latestBySegment.keySet());
        rows.stream().map(Candidate::getSegmentId).filter(Objects::nonNull).forEach(segmentIds::add);
        Map<UUID, Segment> segmentById = (segmentIds.isEmpty() ? List.<Segment>of() : segments.findByIdsScoped(segmentIds))
                .stream().collect(Collectors.toMap(Segment::getId, Function.identity()));
        Set<UUID> documentIds = segmentById.values().stream().map(Segment::getDocumentId).collect(Collectors.toSet());
        Map<UUID, IngestDocument> documentById = (documentIds.isEmpty() ? List.<IngestDocument>of()
                : documents.findByIdsScoped(documentIds)).stream()
                .collect(Collectors.toMap(IngestDocument::getId, Function.identity()));

        List<ReadPart> parts = new ArrayList<>();
        List<String> searched = new ArrayList<>();
        List<String> stillReading = new ArrayList<>();
        latestBySegment.forEach((segmentId, job) -> {
            Segment s = segmentById.get(segmentId);
            if (s == null) {
                return;
            }
            IngestDocument doc = documentById.get(s.getDocumentId());
            String fileName = doc == null ? "a document" : doc.getFileName();
            int from = s.getPageFrom() == null ? 1 : s.getPageFrom();
            int to = s.getPageTo() != null ? s.getPageTo()
                    : doc != null && doc.getPageCount() != null ? doc.getPageCount() : from - 1;
            boolean finished = FINISHED.contains(job.getStatus());
            List<Integer> notRead = finished ? notRead(job, from, to) : List.of();
            List<Integer> read = finished ? PagesSearched.read(from, to, notRead) : List.of();
            String pages = read.isEmpty() ? null : PagesSearched.describe(read);
            parts.add(new ReadPart(s.getDocumentId(), fileName, segmentId, s.getDocClass(), job.getStatus().name(), pages,
                    notRead));
            if (pages != null) {
                searched.add(pages + " of " + fileName);
            } else if (!finished && job.getStatus() != ExtractJobStatus.FAILED) {
                stillReading.add(fileName);
            }
        });

        Map<String, List<Candidate>> byTerm = rows.stream().filter(c -> c.getNaturalKey() != null)
                .collect(Collectors.groupingBy(Candidate::getNaturalKey, LinkedHashMap::new, Collectors.toList()));
        List<TermCoverage> coverage = new ArrayList<>();
        for (ContractTerm t : ContractTerm.values()) {
            List<Candidate> found = byTerm.getOrDefault(t.key(), List.of());
            if (!found.isEmpty()) {
                List<FoundValue> values = found.stream().map(c -> foundValue(c, classOf(c, segmentById))).toList();
                coverage.add(new TermCoverage(t.key(), t.label(), Status.FOUND, null, proposed(found, segmentById), values));
            } else if (!searched.isEmpty()) {
                String message = "Not found, pages searched: " + String.join("; ", searched)
                        + (stillReading.isEmpty() ? "" : ". Still being read: " + String.join(", ", stillReading));
                coverage.add(new TermCoverage(t.key(), t.label(), Status.NOT_FOUND, message, null, List.of()));
            } else {
                String message = parts.isEmpty()
                        ? "Not read: no general conditions, special conditions or contract data are confirmed in this "
                                + "package."
                        : "Not read yet: " + (stillReading.isEmpty() ? "no page of the conditions of contract was read."
                                : String.join(", ", stillReading) + " is being read.");
                coverage.add(new TermCoverage(t.key(), t.label(), Status.NOT_READ, message, null, List.of()));
            }
        }
        return new ContractTermCoverageDto(NOTICE, parts, coverage);
    }

    /** Pages of the part not read: not sent (not English, no text layer) or failed after the retries. */
    private static List<Integer> notRead(ExtractJob job, int from, int to) {
        Set<Integer> pages = new TreeSet<>();
        for (List<UnreadPage> list : List.of(nonNull(job.getUnreadPages()), nonNull(job.getFailedPages()))) {
            list.stream().map(UnreadPage::page).filter(p -> p >= from && p <= to).forEach(pages::add);
        }
        return new ArrayList<>(pages);
    }

    private static List<UnreadPage> nonNull(List<UnreadPage> list) {
        return list == null ? List.of() : list;
    }

    private static DocClass classOf(Candidate c, Map<UUID, Segment> segmentById) {
        Segment s = c.getSegmentId() == null ? null : segmentById.get(c.getSegmentId());
        return s == null ? DocClass.OTHER : DocClass.parse(s.getDocClass()).orElse(DocClass.OTHER);
    }

    private static FoundValue foundValue(Candidate c, DocClass docClass) {
        Map<String, Object> payload = c.getAcceptedPayload() != null ? c.getAcceptedPayload() : c.getPayload();
        return new FoundValue(c.getId(), docClass.name(), text(payload.get(ContractTerm.VALUE)),
                text(payload.get(ContractTerm.CLAUSE)), c.getStatus().name(), overridden(c), c.getSupersedesCandidateId());
    }

    /**
     * The value proposed among those read: not one the special conditions restate, then the special
     * conditions' or contract data's, then applied, accepted and proposed in that order, then the latest.
     */
    private static UUID proposed(List<Candidate> found, Map<UUID, Segment> segmentById) {
        return found.stream()
                .filter(c -> c.getStatus() != CandidateStatus.REJECTED && c.getStatus() != CandidateStatus.SUPERSEDED)
                .max(Comparator.comparing((Candidate c) -> !overridden(c))
                        .thenComparing(c -> classOf(c, segmentById) == DocClass.SCC
                                || classOf(c, segmentById) == DocClass.CONTRACT_DATA)
                        .thenComparingInt(c -> switch (c.getStatus()) {
                            case APPLIED -> 3;
                            case ACCEPTED -> 2;
                            default -> 1;
                        })
                        .thenComparing(Candidate::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(Candidate::getId).orElse(null);
    }

    private static boolean overridden(Candidate c) {
        return c.getFlags().stream().anyMatch(f -> TermOverrides.OVERRIDDEN_BY_SCC.equals(f.code()));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private void requirePackage(UUID packageId) {
        if (packages.findByIdScoped(packageId).isEmpty()) {
            throw new ResourceNotFoundException("No document package " + packageId);
        }
    }
}
