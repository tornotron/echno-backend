package org.tornotron.echno_backend.modules.documentingestion.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;

/** A file in a package, with its latest parse job if it has one. */
public record DocumentDto(UUID id, UUID packageId, UUID parentDocumentId, String fileName, DocumentFileType fileType,
                          long sizeBytes, DocumentStatus status, String error, String sha256, Integer pageCount,
                          Integer sheetCount, Integer rowCount, Integer pagesOcr, Integer pagesFailed, String docClass,
                          String revisionLabel, UUID supersedesDocumentId, LocalDateTime createdAt,
                          ParseJobDto latestJob) {

    public static DocumentDto of(IngestDocument d, ParseJobDto latestJob) {
        return new DocumentDto(d.getId(), d.getPackageId(), d.getParentDocumentId(), d.getFileName(), d.getFileType(),
                d.getSizeBytes(), d.getStatus(), d.getError(), d.getSha256(), d.getPageCount(), d.getSheetCount(),
                d.getRowCount(), d.getPagesOcr(), d.getPagesFailed(), d.getDocClass(), d.getRevisionLabel(),
                d.getSupersedesDocumentId(), d.getCreatedAt(), latestJob);
    }
}
