package org.tornotron.echno_backend.modules.documentingestion.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * review like any other candidate.
 */
@Service
public class RevisionService {

    /** The most BOQ rows read from each of the two files. */
    static final int MAX_ROWS = 20_000;
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
            Map<String, Candidate> before = byCode(candidates.findForDocument(doc.getSupersedesDocumentId(),
                    CandidateKind.BOQ_ITEM, PageRequest.of(0, MAX_ROWS)));
            Map<String, Candidate> after = byCode(candidates.findForDocument(documentId, CandidateKind.BOQ_ITEM,
                    PageRequest.of(0, MAX_ROWS)));
            if (after.isEmpty()) {
                throw new InvalidRequestException("Read the BOQ of " + doc.getFileName() + " first, then compare it");
            }
            java.util.Set<String> existing = new java.util.HashSet<>();
            candidates.findForDocument(documentId, CandidateKind.CHANGE, PageRequest.of(0, MAX_ROWS))
                    .forEach(c -> existing.add(c.getNaturalKey()));
            List<ProposedCandidate> proposed = new ArrayList<>();
            int added = 0;
            int removed = 0;
            int compared = 0;
            for (Map.Entry<String, Candidate> e : after.entrySet()) {
                Candidate revised = e.getValue();
                Candidate earlier = before.get(e.getKey());
                Map<String, SourceRef> refs = refs(revised);
                if (earlier == null) {
                    added++;
                    change(proposed, existing, doc, revised, e.getKey(), null, null, value(revised, BoqField.QUANTITY),
                            refs.get(BoqField.ITEM_CODE.key()), new CandidateFlag(ChangeMatcher.ITEM_ADDED,
                                    "This item is in the revised file only."));
                    continue;
                }
                compared++;
                for (BoqField f : COMPARED) {
                    String old = value(earlier, f);
                    String now = value(revised, f);
                    if (now != null && (old == null || !ChangeMatcher.same(old, now))) {
                        change(proposed, existing, doc, revised, e.getKey(), f.key(), old, now, refs.get(f.key()), null);
                    }
                }
            }
            for (Map.Entry<String, Candidate> e : before.entrySet()) {
                if (!after.containsKey(e.getKey())) {
                    removed++;
                    Candidate earlier = e.getValue();
                    change(proposed, existing, doc, earlier, e.getKey(), null, value(earlier, BoqField.QUANTITY), "0",
                            refs(earlier).get(BoqField.ITEM_CODE.key()), new CandidateFlag(ChangeMatcher.ITEM_REMOVED,
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

    private void change(List<ProposedCandidate> out, java.util.Set<String> existing, IngestDocument doc, Candidate from,
                        String code, String field, String oldValue, String newValue, SourceRef ref, CandidateFlag flag) {
        String key = ChangeMatcher.naturalKey(CandidateKind.BOQ_ITEM.name(), code, field);
        if (ref == null || !existing.add(key)) {
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
        List<ProposedCandidate.ProposedSource> src = new ArrayList<>();
        for (String f : List.of(ChangeMatcher.TARGET, ChangeMatcher.NEW_VALUE)) {
            src.add(new ProposedCandidate.ProposedSource(f, ref.getDocumentId(), ref.getDocumentSha256(), ref.getPage(),
                    ref.getBox(), ref.getSheet(), ref.getRowNumber(), ref.getCell(), ref.getParagraph(), ref.getQuote(),
                    ref.isQuoteVerified()));
        }
        out.add(new ProposedCandidate(doc.getPackageId(), doc.getId(), from.getSegmentId(), CandidateKind.CHANGE, key,
                payload, flag == null ? List.of() : List.of(flag), ExtractionMethod.DETERMINISTIC, src, null));
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

    /** The rows by item code, the latest row for a code winning; rejected and superseded rows left out. */
    private static Map<String, Candidate> byCode(List<Candidate> rows) {
        Map<String, Candidate> out = new LinkedHashMap<>();
        for (Candidate c : rows) {
            if (c.getStatus() == CandidateStatus.REJECTED || c.getStatus() == CandidateStatus.SUPERSEDED) {
                continue;
            }
            String code = value(c, BoqField.ITEM_CODE);
            if (code != null) {
                out.put(code, c);
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
