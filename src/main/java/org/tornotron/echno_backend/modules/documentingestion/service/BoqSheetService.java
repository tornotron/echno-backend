package org.tornotron.echno_backend.modules.documentingestion.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.SqlStateDetector;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.documentingestion.domain.BoqPreviewRow;
import org.tornotron.echno_backend.modules.documentingestion.domain.BoqSheet;
import org.tornotron.echno_backend.modules.documentingestion.domain.BoqSheetStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.Segment;
import org.tornotron.echno_backend.modules.documentingestion.dto.BoqMappingRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.BoqSheetDto;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqCandidates;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqField;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqHeaderDetector;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqSheetExtractor;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow;
import org.tornotron.echno_backend.modules.documentingestion.parsing.WorkbookMeta;
import org.tornotron.echno_backend.modules.documentingestion.repository.BoqSheetRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestDocumentRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SegmentRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * BOQ reading from a parsed workbook, in the order a person works: find each sheet's header and
 * propose a column mapping, then read a sheet under the mapping the person confirms. Reading writes
 * candidates only. Rows go in batches of {@value #BATCH} per transaction, so a 2,000-row sheet needs
 * no long transaction; reading a sheet again first removes its unapplied rows, so a retry after a
 * failure midway leaves no duplicate.
 */
@Service
public class BoqSheetService {

    static final int BATCH = 200;
    static final int PREVIEW_ROWS = 30;
    static final int PREVIEW_COLUMNS = 30;
    static final int PREVIEW_TEXT = 120;
    static final String BOQ_CLASS = "BOQ";

    private final IngestDocumentRepository documents;
    private final IngestPackageRepository packages;
    private final BoqSheetRepository sheets;
    private final SegmentRepository segments;
    private final CandidateRepository candidates;
    private final CandidateWriter writer;
    private final ParsedOutputReader outputs;
    private final TransactionRetryTemplate retry;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;

    public BoqSheetService(IngestDocumentRepository documents, IngestPackageRepository packages,
                           BoqSheetRepository sheets, SegmentRepository segments, CandidateRepository candidates,
                           CandidateWriter writer, ParsedOutputReader outputs, TransactionRetryTemplate retry,
                           TenantEntityHelper tenantEntityHelper, UserContextService userContextService) {
        this.documents = documents;
        this.packages = packages;
        this.sheets = sheets;
        this.segments = segments;
        this.candidates = candidates;
        this.writer = writer;
        this.outputs = outputs;
        this.retry = retry;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
    }

    /**
     * Finds a header on each sheet not yet seen and proposes a mapping. Sheets already found keep
     * their mapping, so calling this again changes nothing a person confirmed.
     */
    public List<BoqSheetDto> detect(UUID documentId) {
        IngestDocument doc = retry.execute("DocumentIngestion.boqDocument", () -> requireWorkbook(documentId, true));
        WorkbookMeta meta = outputs.meta(doc.getOutputPrefix());
        List<TableRow> rows = outputs.tables(doc.getOutputPrefix());
        // Two detections at once meet on the unique (document, sheet) key; the second re-reads and skips.
        retry.execute("DocumentIngestion.boqDetect",
                failure -> SqlStateDetector.carriesSqlState(failure, SqlStateDetector.UNIQUE_VIOLATION), () -> {
            Set<Integer> known = new HashSet<>();
            sheets.findForDocument(documentId).forEach(s -> known.add(s.getSheetIndex()));
            for (WorkbookMeta.Sheet sheet : meta.sheets()) {
                if (known.contains(sheet.index())) {
                    continue;
                }
                List<TableRow> sheetRows = rowsOf(rows, sheet);
                BoqSheet s = new BoqSheet();
                s.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
                s.setPackageId(doc.getPackageId());
                s.setDocumentId(documentId);
                s.setSheetName(cut(sheet.name() == null ? "Sheet " + (sheet.index() + 1) : sheet.name(), 100));
                s.setSheetIndex(sheet.index());
                s.setHidden(sheet.hidden());
                s.setRowCount(sheetRows.size());
                s.setPreview(preview(sheetRows));
                BoqHeaderDetector.detect(sheetRows).ifPresentOrElse(found -> {
                    s.setHeaderRow(found.headerRow());
                    s.setColumns(keys(found.columns()));
                    s.setStatus(found.complete() ? BoqSheetStatus.DETECTED : BoqSheetStatus.NEEDS_MAPPING);
                }, () -> s.setStatus(BoqSheetStatus.NEEDS_MAPPING));
                sheets.saveAndFlush(s);
            }
            return null;
        });
        return list(documentId);
    }

    public List<BoqSheetDto> list(UUID documentId) {
        return retry.execute("DocumentIngestion.boqSheets", () -> {
            requireDocument(documentId);
            return sheets.findForDocument(documentId).stream().map(BoqSheetDto::of).toList();
        });
    }

    /**
     * Confirms a sheet's mapping and reads its rows into BOQ candidates. Refused once any row of the
     * sheet is on a contract, because reading again would leave those rows pointing at an old reading.
     */
    public BoqSheetDto extract(UUID documentId, UUID sheetId, BoqMappingRequest req) {
        Map<BoqField, Integer> columns = columns(req.columns());
        Long userId = userContextService.getCurrentUserId();
        IngestDocument doc = retry.execute("DocumentIngestion.boqDocument", () -> requireWorkbook(documentId, true));
        BoqSheet confirmed = retry.execute("DocumentIngestion.boqConfirm", () -> {
            BoqSheet s = requireSheet(documentId, sheetId);
            if (s.getSegmentId() != null && candidates.countAppliedInSegment(s.getSegmentId()) > 0) {
                throw new InvalidRequestException("Rows of sheet " + s.getSheetName()
                        + " are already on a contract, so the sheet cannot be read again");
            }
            LocalDateTime now = LocalDateTime.now();
            if (s.getSegmentId() == null) {
                Segment segment = new Segment();
                segment.setOrganization(s.getOrganization());
                segment.setDocumentId(documentId);
                segment.setDocClass(BOQ_CLASS);
                segment.setSheetName(s.getSheetName());
                segment.setConfirmedBy(userId);
                segment.setConfirmedAt(now);
                s.setSegmentId(segments.saveAndFlush(segment).getId());
            } else {
                segments.findByIdScoped(s.getSegmentId()).ifPresent(segment -> {
                    segment.setConfirmedBy(userId);
                    segment.setConfirmedAt(now);
                    segments.save(segment);
                });
            }
            s.setHeaderRow(req.headerRow());
            s.setColumns(keys(columns));
            s.setCodePrefix(req.codePrefix() == null || req.codePrefix().isBlank() ? null : req.codePrefix().strip());
            s.setConfirmedBy(userId);
            s.setConfirmedAt(now);
            IngestDocument d = documents.findByIdScoped(documentId).orElseThrow();
            if (d.getDocClass() == null) {
                d.setDocClass(BOQ_CLASS);
                d.setClassSetBy(userId);
                documents.save(d);
            }
            return sheets.save(s);
        });

        List<TableRow> rows = outputs.tables(doc.getOutputPrefix());
        WorkbookMeta.Sheet sheetMeta = new WorkbookMeta.Sheet(confirmed.getSheetIndex(), confirmed.getSheetName(),
                confirmed.isHidden(), null, null);
        BoqSheetExtractor.Result result = BoqSheetExtractor.extract(rowsOf(rows, sheetMeta), confirmed.isHidden(),
                new BoqSheetExtractor.Mapping(req.headerRow(), columns, confirmed.getCodePrefix()));

        UUID segmentId = confirmed.getSegmentId();
        retry.execute("DocumentIngestion.boqDiscard", () -> writer.discardUnapplied(segmentId));
        List<ProposedCandidate> batch = new ArrayList<>(BATCH);
        for (BoqSheetExtractor.Line line : result.lines()) {
            batch.add(BoqCandidates.of(line, doc.getPackageId(), documentId, segmentId, doc.getSha256(),
                    confirmed.getSheetName(), confirmed.getSheetIndex()));
            if (batch.size() == BATCH) {
                List<ProposedCandidate> chunk = List.copyOf(batch);
                retry.execute("DocumentIngestion.boqPropose", () -> writer.proposeAll(chunk));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            List<ProposedCandidate> chunk = List.copyOf(batch);
            retry.execute("DocumentIngestion.boqPropose", () -> writer.proposeAll(chunk));
        }
        return retry.execute("DocumentIngestion.boqSummary", () -> {
            BoqSheet s = requireSheet(documentId, sheetId);
            s.setSummary(result.summary());
            s.setStatus(BoqSheetStatus.EXTRACTED);
            s.setExtractedAt(LocalDateTime.now());
            return BoqSheetDto.of(sheets.save(s));
        });
    }

    private IngestDocument requireDocument(UUID documentId) {
        return documents.findByIdScoped(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("No document " + documentId));
    }

    private IngestDocument requireWorkbook(UUID documentId, boolean open) {
        IngestDocument doc = requireDocument(documentId);
        if (doc.getFileType() != DocumentFileType.XLSX && doc.getFileType() != DocumentFileType.XLS) {
            throw new InvalidRequestException("A BOQ is read from an Excel workbook in this version; "
                    + doc.getFileName() + " is " + doc.getFileType());
        }
        if (doc.getStatus() != DocumentStatus.PARSED || doc.getOutputPrefix() == null) {
            throw new InvalidRequestException(doc.getFileName() + " has not been read yet");
        }
        if (open && packages.findByIdScoped(doc.getPackageId()).map(p -> p.getStatus() != PackageStatus.OPEN)
                .orElse(true)) {
            throw new InvalidRequestException("The package is archived");
        }
        return doc;
    }

    private BoqSheet requireSheet(UUID documentId, UUID sheetId) {
        return sheets.findByIdScoped(sheetId).filter(s -> s.getDocumentId().equals(documentId))
                .orElseThrow(() -> new ResourceNotFoundException("No sheet " + sheetId + " in document " + documentId));
    }

    static Map<BoqField, Integer> columns(Map<String, Integer> requested) {
        Map<BoqField, Integer> columns = new EnumMap<>(BoqField.class);
        Set<Integer> used = new HashSet<>();
        requested.forEach((key, col) -> {
            BoqField field = BoqField.ofKey(key).orElseThrow(() -> new InvalidRequestException(
                    "Unknown BOQ field " + key + "; use itemCode, description, unit, quantity, rate or amount"));
            if (col == null || col < 1) {
                throw new InvalidRequestException("The column of " + key + " must be 1 or more");
            }
            if (!used.add(col)) {
                throw new InvalidRequestException("Column " + col + " is mapped to two fields");
            }
            columns.put(field, col);
        });
        if (!columns.containsKey(BoqField.DESCRIPTION)) {
            throw new InvalidRequestException("Map the description column; a BOQ line is read from it");
        }
        return columns;
    }

    private static Map<String, Integer> keys(Map<BoqField, Integer> columns) {
        Map<String, Integer> out = new LinkedHashMap<>();
        columns.forEach((field, col) -> out.put(field.key(), col));
        return out;
    }

    private static List<TableRow> rowsOf(List<TableRow> rows, WorkbookMeta.Sheet sheet) {
        return rows.stream()
                .filter(r -> r.page() == null)
                .filter(r -> r.sheetIndex() != null ? r.sheetIndex() == sheet.index()
                        : sheet.name() != null && sheet.name().equals(r.sheet()))
                .toList();
    }

    private static List<BoqPreviewRow> preview(List<TableRow> sheetRows) {
        List<BoqPreviewRow> out = new ArrayList<>();
        for (TableRow row : sheetRows) {
            if (out.size() >= PREVIEW_ROWS) {
                break;
            }
            List<BoqPreviewRow.PreviewCell> cells = new ArrayList<>();
            for (TableRow.Cell c : row.cells()) {
                if (c.col() <= PREVIEW_COLUMNS && !c.isEmpty()) {
                    cells.add(new BoqPreviewRow.PreviewCell(c.col(), c.ref(), cut(c.shownText(), PREVIEW_TEXT)));
                }
            }
            out.add(new BoqPreviewRow(row.row(), row.hidden(), cells));
        }
        return out;
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }
}
