package org.tornotron.echno_backend.modules.documentingestion.api;

import java.util.UUID;

/**
 * Published when the worker's reading of a document has been loaded: its {@code pages.jsonl} and
 * {@code tables.jsonl} are under {@code outputPrefix}. Nothing listens in this version; the seam is for
 * the assistant (design section 12), which could read the pages, OCR included, instead of parsing the
 * PDF a second time.
 *
 * @param documentId     The document
 * @param organizationId Its organization; a listener must pin this tenant before reading anything
 * @param projectId      The package's project, or null for a tender with none
 * @param outputPrefix   Object-store prefix of the outputs, trailing slash included
 */
public record DocumentTextReady(UUID documentId, Long organizationId, Long projectId, String outputPrefix) {
}
