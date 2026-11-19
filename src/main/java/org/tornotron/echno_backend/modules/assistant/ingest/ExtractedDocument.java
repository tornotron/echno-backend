package org.tornotron.echno_backend.modules.assistant.ingest;

import java.util.List;

/**
 * Text extracted across all pages of a document.
 *
 * @param pages ordered list of extracted pages
 */
public record ExtractedDocument(List<ExtractedPage> pages) {
}
