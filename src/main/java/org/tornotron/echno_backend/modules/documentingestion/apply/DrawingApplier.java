package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionStorageLayout;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.domain.DrawingEntry;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.extraction.drawing.DrawingFields;
import org.tornotron.echno_backend.modules.documentingestion.extraction.drawing.RevisionOrder;
import org.tornotron.echno_backend.modules.documentingestion.extraction.values.TenderValues;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.DrawingEntryRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestDocumentRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Writes accepted drawings to the package's drawing register (design section 4.3), one row per number
 * and revision, with provenance on each value. After each row the number's revision chain is laid out
 * again: rows in revision order, each linked to the one before and after, and the newest read revision
 * marked current. Older revisions are kept. A DWG lands as a row that says it was not read.
 *
 * <p>One value per number and revision is applied at a time: two accepted rows for the same number and
 * revision are refused before any row, so a person rejects one first. A later accepted row replaces the
 * one in the register, and the candidate it replaces is marked superseded.
 */
@Component
@PreAuthorize("isAuthenticated()")
public class DrawingApplier implements IngestionApplier {

    public static final String TARGET = "DRAWING";
    static final int MAX_DRAWINGS = 5_000;
    static final int MAX_HISTORY_ROWS = 50;
    private static final java.time.format.DateTimeFormatter ARRIVAL =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssnnnnnnnnn");

    private final IngestPackageRepository packages;
    private final IngestDocumentRepository documents;
    private final CandidateRepository candidates;
    private final DrawingEntryRepository drawings;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;

    public DrawingApplier(IngestPackageRepository packages, IngestDocumentRepository documents,
                          CandidateRepository candidates, DrawingEntryRepository drawings,
                          TenantEntityHelper tenantEntityHelper, UserContextService userContextService) {
        this.packages = packages;
        this.documents = documents;
        this.candidates = candidates;
        this.drawings = drawings;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
    }

    // Metadata read while the context starts, with no one signed in; every write keeps the class guard.
    @Override
    @PreAuthorize("permitAll()")
    public CandidateKind kind() {
        return CandidateKind.DRAWING;
    }

    /** The register is the module's own table, so its guard is the module's apply guard. */
    @Override
    @PreAuthorize("permitAll()")
    public String targetGuard() {
        return "@access.can('document-ingestion', 'apply')";
    }

    @Override
    public void preflight(UUID packageId) {
        requirePackage(packageId);
        Map<String, Candidate> accepted = new HashMap<>();
        for (Candidate c : candidates.findForReview(packageId, CandidateKind.DRAWING, CandidateStatus.ACCEPTED, null,
                PageRequest.of(0, MAX_DRAWINGS))) {
            Map<String, Object> payload = c.getAcceptedPayload() == null ? c.getPayload() : c.getAcceptedPayload();
            String number = text(payload.get(DrawingFields.NUMBER));
            if (number == null) {
                continue;
            }
            String revision = text(payload.get(DrawingFields.REVISION));
            String key = RevisionOrder.numberKey(number) + "\u0000" + RevisionOrder.revisionKey(revision) + "\u0000"
                    + notRead(c);
            if (accepted.put(key, c) != null) {
                throw new InvalidRequestException("Two rows are accepted for drawing " + number
                        + (revision == null ? " with no revision" : " revision " + revision) + ". Reject one of them first.");
            }
        }
    }

    @Override
    public AppliedTarget apply(Candidate candidate, Map<String, Object> accepted) {
        String number = text(accepted.get(DrawingFields.NUMBER));
        if (number == null) {
            throw new InvalidRequestException("The accepted drawing has no drawing number");
        }
        String revision = text(accepted.get(DrawingFields.REVISION));
        limit("drawing number", number, 100);
        limit("revision", revision, 20);
        String title = text(accepted.get(DrawingFields.TITLE));
        limit("title", title, 500);
        String discipline = text(accepted.get(DrawingFields.DISCIPLINE));
        limit("discipline", discipline, 60);
        String date = text(accepted.get(DrawingFields.DATE));
        limit("date", date, 60);
        String scale = text(accepted.get(DrawingFields.SCALE));
        limit("scale", scale, 60);
        String numberKey = RevisionOrder.numberKey(number);
        String revisionKey = RevisionOrder.revisionKey(revision);
        limit("revision", revisionKey, 20);

        IngestPackage p = requirePackage(candidate.getPackageId());
        boolean notRead = notRead(candidate);
        DrawingEntry.Status status = notRead ? DrawingEntry.Status.NOT_READ : DrawingEntry.Status.READ;
        DrawingEntry row = drawings.findRow(p.getId(), numberKey, revisionKey, status).orElseGet(() -> {
            DrawingEntry created = new DrawingEntry();
            created.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
            created.setPackageId(p.getId());
            created.setNumberKey(numberKey);
            created.setRevisionKey(revisionKey);
            created.setStatus(status);
            return created;
        });
        if (row.getCandidateId() != null && !row.getCandidateId().equals(candidate.getId())) {
            candidates.findByIdScoped(row.getCandidateId())
                    .filter(prior -> prior.getStatus() == CandidateStatus.APPLIED)
                    .ifPresent(prior -> {
                        prior.setStatus(CandidateStatus.SUPERSEDED);
                        candidates.save(prior);
                    });
        }
        Map<String, Object> extracted = candidate.getPayload();
        row.setProjectId(p.getProjectId());
        row.setDrawingNumber(number);
        row.setRevision(revision);
        row.setTitle(title);
        row.setDiscipline(discipline);
        row.setRevisionDateText(date);
        row.setRevisionDate(TenderValues.date(date).orElse(null));
        row.setScale(scale);
        row.setRevisionHistory(history(accepted.get(DrawingFields.HISTORY)));
        row.setNote(notRead ? DrawingFields.NOT_READ_MESSAGE : null);
        row.setSourceFormat(format(candidate));
        row.setDocumentId(candidate.getDocumentId());
        Integer page = extracted.get(DrawingFields.SHEET_PAGE) instanceof Number n ? n.intValue() : null;
        row.setSheetPage(page);
        row.setLayout(cut(text(extracted.get(DrawingFields.LAYOUT)), 255));
        // The crop's key is composed here from the page, never taken from the payload as written.
        row.setCropImage(page != null && extracted.get(DrawingFields.CROP_IMAGE) != null
                ? DocumentIngestionStorageLayout.titleBlockImage(page) : null);
        row.setCandidateId(candidate.getId());
        row.setUpdatedBy(userContextService.getCurrentUserId());
        DrawingEntry saved = drawings.saveAndFlush(row);
        relink(p.getId(), numberKey);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(DrawingFields.NUMBER, "drawingNumber");
        put(fields, accepted, DrawingFields.REVISION, "revision");
        put(fields, accepted, DrawingFields.TITLE, "title");
        put(fields, accepted, DrawingFields.DISCIPLINE, "discipline");
        put(fields, accepted, DrawingFields.DATE, "revisionDate");
        put(fields, accepted, DrawingFields.SCALE, "scale");
        put(fields, accepted, DrawingFields.HISTORY, "revisionHistory");
        return new AppliedTarget(TARGET, saved.getId().toString(), fields);
    }

    /**
     * Lays out one number's chain: rows in revision order, oldest first, each linked to its neighbours,
     * and the newest read revision current. A DWG row that was not read is current only while no revision
     * of the number has been read.
     */
    void relink(UUID packageId, String numberKey) {
        List<DrawingEntry> chain = new ArrayList<>(drawings.findChain(packageId, numberKey));
        Comparator<DrawingEntry> order = (a, b) -> RevisionOrder.compare(a.getRevision(), a.getRevisionDate(),
                arrival(a), b.getRevision(), b.getRevisionDate(), arrival(b));
        List<DrawingEntry> read = chain.stream().filter(d -> d.getStatus() == DrawingEntry.Status.READ)
                .sorted(order).toList();
        List<DrawingEntry> notRead = chain.stream().filter(d -> d.getStatus() == DrawingEntry.Status.NOT_READ)
                .sorted(order).toList();
        List<DrawingEntry> ordered = new ArrayList<>(notRead);
        ordered.addAll(read);
        DrawingEntry current = ordered.isEmpty() ? null : ordered.get(ordered.size() - 1);
        for (int i = 0; i < ordered.size(); i++) {
            DrawingEntry d = ordered.get(i);
            UUID before = i == 0 ? null : ordered.get(i - 1).getId();
            UUID after = i == ordered.size() - 1 ? null : ordered.get(i + 1).getId();
            boolean isCurrent = d == current;
            if (!Objects.equals(before, d.getSupersedesId()) || !Objects.equals(after, d.getSupersededById())
                    || d.isCurrent() != isCurrent) {
                d.setSupersedesId(before);
                d.setSupersededById(after);
                d.setCurrent(isCurrent);
                drawings.save(d);
            }
        }
    }

    /** Arrival order: when the row was created, then its id, so the order is total. */
    private static String arrival(DrawingEntry d) {
        return (d.getCreatedAt() == null ? "" : ARRIVAL.format(d.getCreatedAt())) + d.getId();
    }

    private boolean notRead(Candidate c) {
        return DocumentFileType.DWG.name().equals(format(c));
    }

    /** The file type of the candidate's document; the payload's own word is not trusted for it. */
    private String format(Candidate c) {
        return documents.findByIdScoped(c.getDocumentId()).map(IngestDocument::getFileType)
                .map(DocumentFileType::name)
                .orElseThrow(() -> new ResourceNotFoundException("The drawing's document no longer exists"));
    }

    private static List<Map<String, Object>> history(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        if (list.size() > MAX_HISTORY_ROWS) {
            throw new InvalidRequestException("The revision history has more than " + MAX_HISTORY_ROWS + " rows");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new InvalidRequestException("A row of the revision history is not a revision, date and description");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (String k : List.of(DrawingFields.HISTORY_REVISION, DrawingFields.HISTORY_DATE,
                    DrawingFields.HISTORY_DESCRIPTION)) {
                String v = text(m.get(k));
                limit("revision history " + k, v, 500);
                row.put(k, v);
            }
            rows.add(row);
        }
        return rows;
    }

    private static void put(Map<String, String> fields, Map<String, Object> accepted, String field, String target) {
        if (accepted.get(field) != null) {
            fields.put(field, target);
        }
    }

    private static void limit(String what, String value, int max) {
        if (value != null && value.length() > max) {
            throw new InvalidRequestException("The " + what + " \"" + cut(value, 40) + "...\" is longer than " + max
                    + " characters; correct it in the review first");
        }
    }

    private IngestPackage requirePackage(UUID packageId) {
        return packages.findByIdScoped(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("No document package " + packageId));
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
