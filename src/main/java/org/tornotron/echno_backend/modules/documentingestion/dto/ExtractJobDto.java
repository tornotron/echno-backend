package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJob;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;

@Schema(description = "One reader's run over a document or a segment, on the extract queue.")
public record ExtractJobDto(
        UUID id,
        UUID documentId,
        @Schema(description = "Null for a job over a whole document, such as classification", nullable = true) UUID segmentId,
        String extractorKey,
        String extractorVersion,
        @Schema(description = "WAITING_LLM and PAUSED_BUDGET keep what is done and resume by themselves; PARTIAL names the pages not read")
        ExtractJobStatus status,
        int attempt,
        int maxAttempts,
        int chunksTotal,
        int chunksDone,
        int chunksFailed,
        @Schema(description = "Pages whose chunk got no usable answer after the retries") List<UnreadPage> failedPages,
        @Schema(description = "Pages not sent: not in English, or not readable by the file reader") List<UnreadPage> unreadPages,
        @Schema(description = "Why the job waits, paused or failed, in one plain sentence", nullable = true) String message,
        LocalDateTime queuedAt,
        @Schema(nullable = true) LocalDateTime startedAt,
        @Schema(nullable = true) LocalDateTime finishedAt) {

    public static ExtractJobDto of(ExtractJob j) {
        return new ExtractJobDto(j.getId(), j.getDocumentId(), j.getSegmentId(), j.getExtractorKey(),
                j.getExtractorVersion(), j.getStatus(), j.getAttempt(), j.getMaxAttempts(), j.getChunksTotal(),
                j.getChunksDone(), j.getChunksFailed(), j.getFailedPages() == null ? List.of() : j.getFailedPages(),
                j.getUnreadPages() == null ? List.of() : j.getUnreadPages(), j.getMessage(), j.getQueuedAt(),
                j.getStartedAt(), j.getFinishedAt());
    }
}
