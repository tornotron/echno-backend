package org.tornotron.echno_backend.modules.documentingestion.domain;

/**
 * Where a document is. {@code STORED_ONLY} is a file kept and listed but never read: a DWG, which
 * no library we ship can open, so the person is asked for a PDF or DXF copy.
 */
public enum DocumentStatus {
    AWAITING_UPLOAD,
    QUEUED,
    PARSED,
    FAILED,
    STORED_ONLY
}
