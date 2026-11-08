package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow;
import org.tornotron.echno_backend.modules.documentingestion.parsing.WorkbookMeta;

/**
 * What an extractor is given about its job, read before it starts: the document, the segment if any,
 * and the worker's outputs. Plain values, so an extractor reaches no repository.
 *
 * @param pages      The pages of the document, or of the segment's page range; empty for a workbook
 * @param meta       The worker's {@code meta.json}
 * @param result     The job's result so far, from earlier attempts and chunks
 * @param segmentId  Null for a document-level job
 * @param pageFrom   The segment's first page, or null
 * @param pageTo     The segment's last page, or null
 * @param tables     The rows of {@code tables.jsonl} in the segment's pages, or every sheet row of a
 *                   workbook; read only for an extractor that asks ({@link Extractor#readsTables()})
 */
public record ExtractContext(UUID jobId, Long organizationId, UUID packageId, UUID documentId, String fileName,
                             DocumentFileType fileType, String documentSha256, UUID segmentId, String segmentClass,
                             Integer pageFrom, Integer pageTo, List<PageText> pages, WorkbookMeta meta,
                             Map<String, Object> result, List<TableRow> tables) {

    public ExtractContext {
        pages = pages == null ? List.of() : List.copyOf(pages);
        result = result == null ? Map.of() : Map.copyOf(result);
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    /** A context with no table rows. */
    public ExtractContext(UUID jobId, Long organizationId, UUID packageId, UUID documentId, String fileName,
                          DocumentFileType fileType, String documentSha256, UUID segmentId, String segmentClass,
                          Integer pageFrom, Integer pageTo, List<PageText> pages, WorkbookMeta meta,
                          Map<String, Object> result) {
        this(jobId, organizationId, packageId, documentId, fileName, fileType, documentSha256, segmentId, segmentClass,
                pageFrom, pageTo, pages, meta, result, List.of());
    }

    public ExtractContext withResult(Map<String, Object> merged) {
        return new ExtractContext(jobId, organizationId, packageId, documentId, fileName, fileType, documentSha256,
                segmentId, segmentClass, pageFrom, pageTo, pages, meta, merged, tables);
    }
}
