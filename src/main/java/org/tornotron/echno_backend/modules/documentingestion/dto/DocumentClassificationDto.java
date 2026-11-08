package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.ClassMethod;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;

@Schema(description = "A document's proposed or confirmed class and page segments, for the class confirmation screen.")
public record DocumentClassificationDto(
        UUID documentId,
        String fileName,
        DocumentFileType fileType,
        DocumentStatus status,
        @Schema(nullable = true) Integer pageCount,
        @Schema(description = "NIT, ITB, ELIGIBILITY, GCC, SCC, CONTRACT_DATA, SCOPE, BOQ, SPECIFICATION, DRAWING, "
                + "MILESTONE_SCHEDULE, CORRIGENDUM or OTHER; null while not yet classified", nullable = true)
        String docClass,
        @Schema(nullable = true) ClassMethod classMethod,
        @Schema(description = "What the class was read from, in one sentence", nullable = true) String classReason,
        boolean confirmed,
        @Schema(nullable = true) Long confirmedBy,
        @Schema(nullable = true) LocalDateTime confirmedAt,
        List<SegmentDto> segments,
        @Schema(description = "Pages not read for classification, with why: shown as \"not read\"") List<UnreadPage> unreadPages,
        @Schema(description = "The latest classification job, or null if none ran", nullable = true) ExtractJobDto classification) {

    public static DocumentClassificationDto of(IngestDocument d, List<SegmentDto> segments, ExtractJobDto job) {
        return new DocumentClassificationDto(d.getId(), d.getFileName(), d.getFileType(), d.getStatus(), d.getPageCount(),
                d.getDocClass(), d.getClassMethod(), d.getClassReason(), d.getClassConfirmedAt() != null,
                d.getClassConfirmedAt() != null ? d.getClassSetBy() : null, d.getClassConfirmedAt(), segments,
                d.getUnreadPages() == null ? List.of() : d.getUnreadPages(), job);
    }
}
