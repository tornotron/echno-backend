package org.tornotron.echno_backend.compliance.job;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One compliance generation job as the caller sees it: what it is doing, how far it has got,
 * and what came of it.
 *
 * <p>The three counts are there so a caller can show something truthful at every point in the
 * run. {@code batchesDone} over {@code batchesTotal} is the progress bar; {@code rulesAssessed}
 * over {@code rulesTotal} is the same thing in the units the user thinks in;
 * {@code createdCount} is meaningful only once {@code status} is terminal, and is zero on a
 * failure by construction, since a run that did not cover every rule creates nothing.
 */
@Schema(description = "A compliance generation run: its state, its progress and its outcome.")
public record ComplianceGenerationJobDto(

        @Schema(description = "Job id; poll this job with GET /compliance/jobs/{id}.")
        UUID id,

        @Schema(description = "The project the compliances are being generated for.")
        Long projectId,

        @Schema(description = "queued and running are in flight. succeeded finished and created "
                + "compliances; nothing-to-report finished having assessed every rule and found "
                + "nothing to create, which is a result and not a failure; failed did not cover "
                + "every rule and created nothing.")
        ComplianceJobStatus status,

        @Schema(description = "Candidate rules for this project's jurisdiction.")
        int rulesTotal,

        @Schema(description = "Rules assessed so far. Progress only: it is not a partial result.")
        int rulesAssessed,

        @Schema(description = "Model calls the run is split into.")
        int batchesTotal,

        @Schema(description = "Model calls completed so far.")
        int batchesDone,

        @Schema(description = "Compliance inspections created. Meaningful once the status is terminal.")
        int createdCount,

        @Schema(description = "Why the last attempt did not finish, in plain words. Present while a "
                + "job is being retried as well as on final failure.")
        String errorMessage,

        @Schema(description = "Attempts started so far.")
        int attempt,

        @Schema(description = "Attempts before the job gives up.")
        int maxAttempts,

        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    static ComplianceGenerationJobDto from(ComplianceGenerationJob job) {
        return new ComplianceGenerationJobDto(
                job.getId(),
                job.getProjectId(),
                job.getStatus(),
                job.getRulesTotal(),
                job.getRulesAssessed(),
                job.getBatchesTotal(),
                job.getBatchesDone(),
                job.getCreatedCount(),
                job.getErrorMessage(),
                job.getAttempt(),
                job.getMaxAttempts(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt());
    }
}
