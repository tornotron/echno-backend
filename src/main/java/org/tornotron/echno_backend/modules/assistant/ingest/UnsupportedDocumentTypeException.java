package org.tornotron.echno_backend.modules.assistant.ingest;

/**
 * Thrown when an uploaded document's MIME type or extension is not supported for ingestion.
 * Currently only PDF and plain text documents are supported.
 */
public class UnsupportedDocumentTypeException extends RuntimeException {

    public UnsupportedDocumentTypeException(String message) {
        super(message);
    }
}
