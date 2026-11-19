package org.tornotron.echno_backend.modules.assistant.ingest;

/**
 * A chunk of text prepared for embedding and storage in the vector index.
 *
 * @param chunkIndex  sequence order of the chunk across the document
 * @param pageNo      1-based page number
 * @param charStart   character start offset within the page
 * @param charEnd     character end offset within the page
 * @param content     the chunk text
 * @param contentHash SHA-256 hash of the chunk content, to avoid re-embedding on re-ingest
 */
public record DocChunk(
        int chunkIndex,
        int pageNo,
        int charStart,
        int charEnd,
        String content,
        String contentHash
) {
}
