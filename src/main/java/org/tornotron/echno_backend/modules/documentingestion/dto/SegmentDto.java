package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.ClassMethod;
import org.tornotron.echno_backend.modules.documentingestion.domain.Segment;
import org.tornotron.echno_backend.modules.documentingestion.domain.SegmentStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;

@Schema(description = "A part of a document holding one class: a page range of a PDF, or a sheet of a workbook.")
public record SegmentDto(
        UUID id,
        String docClass,
        @Schema(nullable = true) Integer pageFrom,
        @Schema(nullable = true) Integer pageTo,
        @Schema(nullable = true) String sheetName,
        @Schema(description = "PROPOSED until a person confirms it; extraction starts only after", nullable = true)
        SegmentStatus status,
        @Schema(nullable = true) ClassMethod method,
        @Schema(description = "What the class was read from, in one sentence", nullable = true) String reason,
        @Schema(nullable = true) Long confirmedBy,
        @Schema(nullable = true) LocalDateTime confirmedAt,
        @Schema(description = "Pages of the segment that were not read, with why") List<UnreadPage> unreadPages) {

    public static SegmentDto of(Segment s) {
        return new SegmentDto(s.getId(), s.getDocClass(), s.getPageFrom(), s.getPageTo(), s.getSheetName(),
                s.getStatus() == null && s.isConfirmed() ? SegmentStatus.CONFIRMED : s.getStatus(), s.getMethod(),
                s.getReason(), s.getConfirmedBy(), s.getConfirmedAt(),
                s.getUnreadPages() == null ? List.of() : s.getUnreadPages());
    }
}
