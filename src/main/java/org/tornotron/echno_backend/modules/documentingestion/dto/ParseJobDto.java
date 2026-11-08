package org.tornotron.echno_backend.modules.documentingestion.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJob;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJobStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJobType;

public record ParseJobDto(UUID id, ParseJobType jobType, ParseJobStatus status, int attempt, int maxAttempts,
                          String error, LocalDateTime queuedAt, LocalDateTime startedAt, LocalDateTime finishedAt) {

    public static ParseJobDto of(ParseJob j) {
        return new ParseJobDto(j.getId(), j.getJobType(), j.getStatus(), j.getAttempt(), j.getMaxAttempts(),
                j.getError(), j.getQueuedAt(), j.getStartedAt(), j.getFinishedAt());
    }
}
