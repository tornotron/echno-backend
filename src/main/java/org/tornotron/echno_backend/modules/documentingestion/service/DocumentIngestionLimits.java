package org.tornotron.echno_backend.modules.documentingestion.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The upload limits of section 9 of the design. Checked at presign on what the browser declares,
 * and again by the worker on the real object, since a presigned PUT does not bind the size.
 */
@Component
public class DocumentIngestionLimits {

    private final long maxFileBytes;
    private final long maxZipBytes;
    private final int maxDocumentsPerPackage;

    public DocumentIngestionLimits(
            @Value("${echno.modules.document-ingestion.max-file-bytes:209715200}") long maxFileBytes,
            @Value("${echno.modules.document-ingestion.max-zip-bytes:1073741824}") long maxZipBytes,
            @Value("${echno.modules.document-ingestion.max-documents-per-package:500}") int maxDocumentsPerPackage) {
        this.maxFileBytes = maxFileBytes;
        this.maxZipBytes = maxZipBytes;
        this.maxDocumentsPerPackage = maxDocumentsPerPackage;
    }

    public long maxFileBytes() {
        return maxFileBytes;
    }

    public long maxZipBytes() {
        return maxZipBytes;
    }

    public int maxDocumentsPerPackage() {
        return maxDocumentsPerPackage;
    }
}
