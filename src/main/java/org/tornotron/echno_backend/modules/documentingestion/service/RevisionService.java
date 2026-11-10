package org.tornotron.echno_backend.modules.documentingestion.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractionMethod;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.SourceRef;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.RevisionDiffDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.RevisionRequest;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqField;
import org.tornotron.echno_backend.modules.documentingestion.extraction.corrigendum.ChangeLinker;
import org.tornotron.echno_backend.modules.documentingestion.extraction.corrigendum.ChangeMatcher;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestDocumentRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SourceRefRepository;

/**
 * The revision chain of design section 4.8: a document marked as replacing an earlier one, and the
 * row-by-row comparison of a revised BOQ with the BOQ it replaces, with no LLM. Every changed field of an
 * item is one {@code CHANGE} candidate citing the revised file's cell; an item only in the revised file, or
 * only in the earlier one, is a change flagged as added or removed. Nothing is applied here: changes go to
 * review like any other candidate. Each change records the file it was compared against, so changing that
 * file is refused while its changes are in review.
 */
@Service
public class RevisionService {

    /** How many rows are read at a time; every page is read before comparing. */
    static final int PAGE = 5_000;

    /** On a change from a comparison: the file the revised one was compared against. */
    public static final String BASELINE = "baselineDocumentId";
    static final List<BoqField> COMPARED = List.of(BoqField.DESCRIPTION, BoqField.UNIT, BoqField.QUANTITY, BoqField.RATE);

    private final IngestDocumentRepository documents;
    private final IngestPackageRepository packages;
    private final CandidateRepository candidates;
    private final SourceRefRepository sources;
    private final CandidateWriter writer;
    private final ChangeLinker linker;
    private final TransactionRetryTemplate retry;

    public RevisionService(IngestDocumentRepository documents, IngestPackageRepository packages,
                           CandidateRepository candidates, SourceRefRepository sources, CandidateWriter writer,
                           ChangeLinker linker, TransactionRetryTemplate retry) {
        this.documents = documents;
        this.packages = packages;
        this.candidates = candidates;
        this.sources = sources;
        this.writer = writer;
        this.linker = linker;
        this.retry = retry;
    }

    public DocumentDto markRevision(UUID documentId, RevisionRequest req) {
        return retry.execute("DocumentIngestion.markRevision", () -> {
            IngestDocument doc = requireDocument(documentId);
            requireOpen(doc.getPackageId());
            IngestDocument earlier = requireDocument(req.supersedesDocumentId());
            if (!earlier.getPackageId().equals(doc.getPackageId())) {
                throw new InvalidRequestException("A revision must be of a document in the same package");
            }
            if (earlier.getId().equals(doc.getId())) {
                throw new InvalidRequestException("A document cannot be a revision of itself");
            }
            if (doc.getSupersedesDocumentId() != null && !doc.getSupersedesDocumentId().equals(earlier.getId())
                    && comparisonChanges(doc.getId()).stream().anyMatch(c -> c.getStatus() != CandidateStatus.REJECTED)) {
                throw new InvalidRequestException("Changes from comparing " + doc.getFileName() + " with the file it "
                        + "replaces are still in review. Reject them first, then change the file it replaces.");
            }
            for (UUID step = earlier.getSupersedesDocumentId(); step != null; ) {
                if (step.equals(doc.getId())) {
                    throw new InvalidRequestException("That would make the revision chain a loop");
                }
                step = documents.findByIdScoped(step).map(IngestDocument::getSupersedesDocumentId).orElse(null);
            }
            doc.setSupersedesDocumentId(earlier.getId());
            doc.setRevisionLabel(req.revisionLabel() == null || req.revisionLabel().isBlank() ? null
                    : req.revisionLabel().strip());
            return DocumentDto.of(documents.save(doc), null);
        });
    }

    /** Compares the revised document's BOQ rows with those of the document it replaces. Run again, it adds nothing. */
    public RevisionDiffDto diff(UUID documentId) {
        return retry.execute("DocumentIngestion.revisionDiff", () -> {
            IngestDocument doc = requireDocument(documentId);
            requireOpen(doc.getPackageId());
            if (doc.getSupersedesDocumentId() == null) {
                throw new InvalidRequestException(doc.getFileName() + " is not marked as a revision of another file");
            }
            UUID baseline = doc.getSupersedesDocumentId();
            Map<String, Candidate> before = byCode(all(baseline, CandidateKind.BOQ_ITEM));
            Map<String, Candidate> after = byCode(all(documentId, CandidateKind.BOQ_ITEM));
            if (after.isEmpty()) {
                throw new InvalidRequestException("Read the BOQ of " + doc.getFileName() + " first, then compare it");
            }
            if (before.isEmpty()) {
                String name = documents.findByIdScoped(baseline).map(IngestDocument::getFileName).orElse("the earlier file");
                throw new InvalidRequestException("Read the BOQ of " + name + " first, then compare " + doc.getFileName()
                        + " with it");
            }
            Set<String> existing = new HashSet<>();
            comparisonChanges(documentId).stream()
                    .filter(c -> baseline.toString().equals(String.valueOf(c.getPayload().get(BASELINE))))
                    .forEach(c -> existing.add(c.getNaturalKey()));
            UUID revisedSegment = after.values().iterator().next().getSegmentId();
            List<ProposedCandidate> proposed = new ArrayList<>();
            int added = 0;
            int removed = 0;
            int compared = 0;
            for (Map.Entry<String, Candidate> e : after.entrySet()) {
                Candidate revised = e.getValue();
                Candidate earlier = before.get(e.getKey());
                Map<String, SourceRef> refs = refs(revised);
                String code = value(revised, BoqField.ITEM_CODE);
                SourceRef codeRef = refs.get(BoqField.ITEM_CODE.key());
                if (earlier == null) {
                    added++;
                    change(proposed, existing, doc, baseline, revised.getSegmentId(), code, null, null,
                            value(revised, BoqField.QUANTITY), codeRef, refs.get(BoqField.QUANTITY.key()),
                            new CandidateFlag(ChangeMatcher.ITEM_ADDED, "This item is in the revised file only."));
                    continue;
                }
                compared++;
                for (BoqField f : COMPARED) {
                    String old = value(earlier, f);
                    String now = value(revised, f);
                    if (now != null && (old == null || !ChangeMatcher.sameValue(f, old, now))) {
                        change(proposed, existing, doc, baseline, revised.getSegmentId(), code, f.key(), old, now, codeRef,
                                refs.get(f.key()), null);
                    }
                }
            }
            for (Map.Entry<String, Candidate> e : before.entrySet()) {
                if (!after.containsKey(e.getKey())) {
                    removed++;
                    Candidate earlier = e.getValue();
                    // No new value: the removal is inferred from the row's absence, so only the earlier row is cited.
                    change(proposed, existing, doc, baseline, revisedSegment, value(earlier, BoqField.ITEM_CODE), null,
                            value(earlier, BoqField.QUANTITY), null, refs(earlier).get(BoqField.ITEM_CODE.key()), null,
                            new CandidateFlag(ChangeMatcher.ITEM_REMOVED,
                                    "This item is in the earlier file only; the revised file leaves it out."));
                }
            }
            List<UUID> ids = writer.proposeAll(proposed);
            List<Candidate> written = new ArrayList<>();
            ids.forEach(id -> candidates.findByIdScoped(id).ifPresent(written::add));
            linker.link(written, doc.getPackageId());
            return new RevisionDiffDto(compared, ids.size(), added, removed);
        });
    }

    /**
     * One change of a comparison. The item code is cited from its cell, and the new value from the cell that
     * holds it; a change with no new value, a removal, cites the code only.
     */
    private void change(List<ProposedCandidate> out, Set<String> existing, IngestDocument doc, UUID baseline,
                        UUID segmentId, String code, String field, String oldValue, String newValue, SourceRef codeRef,
                        SourceRef valueRef, CandidateFlag flag) {
        String key = ChangeMatcher.naturalKey(CandidateKind.BOQ_ITEM.name(), code, field);
        if (codeRef == null || (newValue != null && valueRef == null) || !existing.add(key)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ChangeMatcher.TARGET_KIND, CandidateKind.BOQ_ITEM.name());
        payload.put(ChangeMatcher.TARGET, code);
        if (field != null) {
            payload.put(ChangeMatcher.FIELD, field);
        }
        if (oldValue != null) {
            payload.put(ChangeMatcher.OLD_VALUE, oldValue);
        }
        if (newValue != null) {
            payload.put(ChangeMatcher.NEW_VALUE, newValue);
        }
        payload.put(ChangeMatcher.ORIGIN, ChangeMatcher.ORIGIN_REVISED_FILE);
        payload.put(BASELINE, baseline.toString());
        List<ProposedCandidate.ProposedSource> src = new ArrayList<>();
        src.add(source(ChangeMatcher.TARGET, codeRef));
        if (newValue != null) {
            src.add(source(ChangeMatcher.NEW_VALUE, valueRef));
        }
        out.add(new ProposedCandidate(doc.getPackageId(), doc.getId(), segmentId, CandidateKind.CHANGE, key,
                payload, flag == null ? List.of() : List.of(flag), ExtractionMethod.DETERMINISTIC, src, null));
    }

    private static ProposedCandidate.ProposedSource source(String field, SourceRef ref) {
        return new ProposedCandidate.ProposedSource(field, ref.getDocumentId(), ref.getDocumentSha256(), ref.getPage(),
                ref.getBox(), ref.getSheet(), ref.getRowNumber(), ref.getCell(), ref.getParagraph(), ref.getQuote(),
                ref.isQuoteVerified());
    }

    /** Every candidate of one kind read from a document, page by page. */
    private List<Candidate> all(UUID documentId, CandidateKind kind) {
        List<Candidate> out = new ArrayList<>();
        for (int page = 0; ; page++) {
            List<Candidate> rows = candidates.findForDocument(documentId, kind, PageRequest.of(page, PAGE));
            out.addAll(rows);
            if (rows.size() < PAGE) {
                return out;
            }
        }
    }

    /** The changes a comparison proposed on a document. */
    private List<Candidate> comparisonChanges(UUID documentId) {
        return all(documentId, CandidateKind.CHANGE).stream()
                .filter(c -> ChangeMatcher.ORIGIN_REVISED_FILE.equals(c.getPayload().get(ChangeMatcher.ORIGIN)))
                .toList();
    }

    private Map<String, SourceRef> refs(Candidate c) {
        Map<String, SourceRef> out = new LinkedHashMap<>();
        sources.findForCandidates(List.of(c.getId())).forEach(s -> out.putIfAbsent(s.getField(), s));
        if (!out.containsKey(BoqField.ITEM_CODE.key()) && !out.isEmpty()) {
            out.put(BoqField.ITEM_CODE.key(), out.values().iterator().next());
        }
        for (BoqField f : COMPARED) {
            out.putIfAbsent(f.key(), out.get(BoqField.ITEM_CODE.key()));
        }
        return out;
    }

    /**
     * The rows read from a file, by item code with case and spacing ignored, the latest row for a code
     * winning. A rejected row is left out. A superseded row stays: the comparison is of the two files as
     * read, so accepting a change from it does not change what it compares. A revision proposed by applying
     * a change is not a row read from the file, so it is left out too.
     */
    private static Map<String, Candidate> byCode(List<Candidate> rows) {
        Map<String, Candidate> out = new LinkedHashMap<>();
        for (Candidate c : rows) {
            if (c.getStatus() == CandidateStatus.REJECTED || c.getSupersedesCandidateId() != null) {
                continue;
            }
            String code = value(c, BoqField.ITEM_CODE);
            if (code != null) {
                out.put(ChangeMatcher.codeKey(code), c);
            }
        }
        return out;
    }

    private static String value(Candidate c, BoqField f) {
        Map<String, Object> p = c.getAcceptedPayload() != null ? c.getAcceptedPayload() : c.getPayload();
        Object v = p.get(f.key());
        return v == null || v.toString().isBlank() ? null : v.toString().strip();
    }

    private IngestDocument requireDocument(UUID id) {
        return documents.findByIdScoped(id).orElseThrow(() -> new ResourceNotFoundException("No document " + id));
    }

    private void requireOpen(UUID packageId) {
        if (packages.findByIdScoped(packageId).map(p -> p.getStatus() != PackageStatus.OPEN).orElse(true)) {
            throw new InvalidRequestException("The package is archived");
        }
    }
}
