package org.tornotron.echno_backend.modules.assistant.ingest;

/**
 * Text extracted from a single page of a document.
 *
 * @param pageNo 1-based page number
 * @param text   extracted text of the page
 */
public record ExtractedPage(int pageNo, String text) {
}
