package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.util.Set;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStage;

/**
 * One reader of documents on the extract queue. The queue owns claiming, chunking, retries, the
 * budget, the ledger and the LLM switch; an extractor owns what it asks, how it reads the answer, and
 * what it writes when the job ends. It writes only module tables, through the extraction package's
 * writers: never a live table, which is the apply step's alone (design section 6.5).
 *
 * <p>Classification is the first extractor and reads whole documents. The per-kind extractors of plan
 * steps 13 and later read confirmed segments of their classes. Each is a bean; an operator turns one
 * off with {@code echno.modules.document-ingestion.extractors.<key>.enabled=false}.
 */
public interface Extractor {

    /** Stable key, on job rows and the ledger, such as {@code classify}. */
    String key();

    /** Bumped when what the extractor reads or writes changes. */
    String version();

    /** The system prompt and its version. */
    PromptSpec prompt();

    /**
     * The segment classes it reads. Empty for an extractor that reads whole documents, which is queued
     * when a document's text arrives rather than when a segment is confirmed.
     */
    Set<DocClass> segmentClasses();

    /**
     * Whether the job reads {@code tables.jsonl} as well as the pages: the rows on the segment's pages
     * of a PDF, or every sheet row of a workbook. Off unless asked, since a large file's tables are
     * not small.
     */
    default boolean readsTables() {
        return false;
    }

    /**
     * Whether a segment of a package at this stage is queued for this extractor. Every stage unless an
     * extractor says otherwise; qualification, for one, reads only while the package is being bid.
     */
    default boolean readsAtStage(PackageStage stage) {
        return true;
    }

    /**
     * Whether the job reads the worker's drawing outputs: {@code titleblock.jsonl} for a PDF and
     * {@code dxf.json} for a DXF. Such an extractor is also queued on a DXF or DWG confirmed as its
     * class, on a segment for the whole file, and on a DWG it is given no outputs at all, since a DWG
     * is stored and not read.
     */
    default boolean readsTitleBlocks() {
        return false;
    }

    /**
     * Does what needs no LLM and says which pages still need reading. Runs on every attempt, so it must
     * not write; what it decides goes in {@link ExtractPlan#result()}.
     */
    ExtractPlan plan(ExtractContext context);

    /** The user message for one chunk. Text from that chunk's pages only, of one document. */
    String userPrompt(ExtractContext context, ChunkPages chunk);

    /**
     * Reads one complete answer. Throws {@link InvalidAnswerException} when it is not the JSON asked
     * for, and the queue sends the chunk once more.
     */
    ChunkOutput read(ExtractContext context, ChunkPages chunk, String answer);

    /**
     * Runs once every chunk has been read or has failed, with the job's merged result. Writes what the
     * extractor produces beyond candidates, such as classification's proposed segments.
     */
    void complete(ExtractContext context, ExtractCompletion completion);
}
