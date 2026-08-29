package org.tornotron.echno_backend.compliance.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.compliance.ComplianceGenerationProgress;
import org.tornotron.echno_backend.compliance.ComplianceGenerationService;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs one claimed compliance generation job to a conclusion, and writes down which
 * conclusion it was.
 *
 * <p>Separate from {@link ComplianceGenerationJobDispatcher} because the two do genuinely
 * different things and fail in different ways. The dispatcher is about threads, timing and
 * who gets which row; this is about what a run means. Keeping them apart also means the
 * part with all the judgement in it, which outcome a given ending is, can be exercised
 * without a thread pool anywhere near it.
 *
 * <h2>Tenancy</h2>
 *
 * <p>It is handed an organization id read off the claimed row, never one inferred from the
 * thread, and everything it does happens inside {@link TenantScopedJobRunner}, which refuses
 * a null org id rather than running unscoped. Both tenant-isolation mechanisms fail open on
 * a missing context, so a worker that forgot this would read every organization's rows and
 * look perfectly healthy doing it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComplianceGenerationJobWorker {

    private final ComplianceGenerationJobRepository jobRepository;
    private final ComplianceGenerationService complianceGenerationService;
    private final TenantScopedJobRunner tenantScopedJobRunner;
    private final TransactionRetryTemplate retryTemplate;
    private final ComplianceJobProperties properties;

    /**
     * Runs the job with this id, which the caller has already claimed for {@code nodeId}.
     *
     * <p>Throws nothing: every ending is recorded on the row, which is the whole point of
     * there being a row.
     *
     * <p>Declared unscoped for the dispatch-row read alone, which is scalars by design: the
     * organization id is what that read is for. {@link TenantScopedJobRunner} then withdraws
     * the declaration and pins the tenant for everything that follows.
     */
    @WithoutTenant("Reading which organization a claimed job belongs to is the step that "
            + "establishes the tenant, so it cannot itself run under one")
    public void run(UUID jobId, String nodeId) {
        Optional<ComplianceGenerationJobRepository.DispatchRow> found = retryTemplate.execute(
                "ComplianceGenerationJobWorker.dispatchRow", () -> jobRepository.findDispatchRow(jobId));
        if (found.isEmpty()) {
            log.warn("Compliance generation job {} vanished between being claimed and being run", jobId);
            return;
        }

        ComplianceGenerationJobRepository.DispatchRow row = found.get();
        Long orgId = row.getOrganizationId();
        Long projectId = row.getProjectId();
        log.info("Running compliance generation job {} for project {} in organization {} (attempt {} of {})",
                jobId, projectId, orgId, row.getAttempt(), row.getMaxAttempts());

        tenantScopedJobRunner.runForTenant(orgId, () -> {
            try {
                // Revalidated here, not only at accept time, because the row outlives the
                // configuration it was accepted under. A restart that lost the AI key would
                // otherwise reach the generation call, be handed the empty list an unconfigured
                // service answers with, and record NOTHING_TO_REPORT for a run that asked
                // nothing, which is precisely the ambiguity the status split exists to remove.
                complianceGenerationService.validateAndCountCandidateRules(projectId, orgId);
                List<InspectionDto> created = complianceGenerationService.generateForProject(
                        projectId, orgId, progressFor(jobId, nodeId, row.getAttempt()));
                finish(jobId, orgId, nodeId, row.getAttempt(), created.size());
            } catch (Exception e) {
                recordFailure(jobId, orgId, nodeId, row, e);
            }
        });
    }

    /**
     * Writes progress as each batch lands, and pushes the lease forward with it.
     *
     * <p>Progress and the lease travel together on purpose. Finishing a batch is the only
     * evidence that this worker is alive and making headway, so it is exactly the moment the
     * claim deserves extending, and a worker that has stopped making headway stops extending
     * it. That removes the need for a separate heartbeat timer and makes the two impossible to
     * get out of step.
     *
     * <p>The update is conditional on this claim still being the row's current one, so a
     * worker whose lease expired and whose job was picked up again cannot write over the new
     * owner's progress. The claim is named by replica and attempt together, not by the
     * replica alone: the requeued job may be re-claimed by this same replica, and only the
     * attempt, which every claim increments, tells the superseded run from the current one.
     */
    private ComplianceGenerationProgress progressFor(UUID jobId, String nodeId, int attempt) {
        return (batchesDone, batchesTotal, rulesAssessed, rulesTotal) -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime leaseUntil = now.plus(properties.getLeaseDuration());
            int updated = retryTemplate.execute("ComplianceGenerationJobWorker.progress", () ->
                    jobRepository.recordProgress(jobId, nodeId, attempt, batchesDone, rulesAssessed,
                            leaseUntil, now));
            if (updated == 0) {
                log.warn("Compliance generation job {} is no longer claimed by {}; its progress "
                        + "({} of {} batches) was not recorded", jobId, nodeId, batchesDone, batchesTotal);
            }
        };
    }

    /**
     * Records a finished run.
     *
     * <p>A run that created nothing is {@code NOTHING_TO_REPORT} rather than a success with a
     * zero count, because the two need different words in front of a user: one says the
     * jurisdiction has nothing outstanding for this project, the other would leave them
     * wondering whether the feature had worked at all. Neither is a failure, and neither may
     * be confused with one. That distinction is the reason the truncation checks were written;
     * losing it here would put the same ambiguity back one layer further away from anyone who
     * could notice.
     */
    private void finish(UUID jobId, Long orgId, String nodeId, int attempt, int createdCount) {
        ComplianceJobStatus status = createdCount > 0
                ? ComplianceJobStatus.SUCCEEDED
                : ComplianceJobStatus.NOTHING_TO_REPORT;
        if (writeTerminal(jobId, orgId, nodeId, attempt, status, createdCount, null)) {
            log.info("Compliance generation job {} finished as {} with {} inspection(s) created",
                    jobId, status.getValue(), createdCount);
        }
    }

    /**
     * Records a run that did not finish, and decides whether it gets another go.
     *
     * <p>A precondition failure is terminal at once. Those are decided from the project row and
     * the configuration, so a second attempt would read the same rows and fail the same way,
     * and retrying would only delay telling the user what to fix. Everything else, which is
     * everything involving the model or the network, is retried until the attempts run out,
     * because those are the failures a second try genuinely fixes.
     *
     * <p>The message is written on every attempt rather than only the last, so a caller polling
     * through a retry is told what is going wrong instead of watching an unexplained pause.
     * Nothing was created either way: a run that did not cover every rule creates nothing, so a
     * retry has no partial state to reconcile with.
     */
    private void recordFailure(UUID jobId,
                               Long orgId,
                               String nodeId,
                               ComplianceGenerationJobRepository.DispatchRow row,
                               Exception failure) {
        boolean deterministic = failure instanceof InvalidRequestException
                || failure instanceof ResourceNotFoundException;
        boolean attemptsLeft = row.getAttempt() < row.getMaxAttempts();
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();

        if (deterministic || !attemptsLeft) {
            log.error("Compliance generation job {} failed for project {}: {}",
                    jobId, row.getProjectId(), message, failure);
            writeTerminal(jobId, orgId, nodeId, row.getAttempt(), ComplianceJobStatus.FAILED, 0, message);
            return;
        }

        log.warn("Compliance generation job {} failed on attempt {} of {} and will be tried again: {}",
                jobId, row.getAttempt(), row.getMaxAttempts(), message);
        requeue(jobId, orgId, nodeId, row.getAttempt(),
                "Attempt " + row.getAttempt() + " of " + row.getMaxAttempts()
                + " did not finish, and it is being tried again. " + message);
    }

    /**
     * Moves a job to a final state, if this replica still owns it.
     *
     * @return whether the write happened
     */
    private boolean writeTerminal(UUID jobId,
                                  Long orgId,
                                  String nodeId,
                                  int attempt,
                                  ComplianceJobStatus status,
                                  int createdCount,
                                  String errorMessage) {
        return retryTemplate.execute("ComplianceGenerationJobWorker.finish", () -> {
            Optional<ComplianceGenerationJob> found = jobRepository.findByIdScoped(jobId, orgId);
            if (found.isEmpty() || !stillOurs(found.get(), jobId, nodeId, attempt)) {
                return false;
            }
            ComplianceGenerationJob job = found.get();
            job.setStatus(status);
            job.setCreatedCount(createdCount);
            job.setErrorMessage(truncate(errorMessage));
            job.setFinishedAt(LocalDateTime.now());
            job.setLeaseExpiresAt(null);
            jobRepository.save(job);
            return true;
        });
    }

    private void requeue(UUID jobId, Long orgId, String nodeId, int attempt, String errorMessage) {
        retryTemplate.executeWithoutResult("ComplianceGenerationJobWorker.retry", () -> {
            Optional<ComplianceGenerationJob> found = jobRepository.findByIdScoped(jobId, orgId);
            if (found.isEmpty() || !stillOurs(found.get(), jobId, nodeId, attempt)) {
                return;
            }
            ComplianceGenerationJob job = found.get();
            job.setStatus(ComplianceJobStatus.QUEUED);
            job.setErrorMessage(truncate(errorMessage));
            job.setClaimedBy(null);
            job.setLeaseExpiresAt(null);
            job.setBatchesDone(0);
            job.setRulesAssessed(0);
            jobRepository.save(job);
        });
    }

    /**
     * Whether the claim this run started under is still the row's current one.
     *
     * <p>A worker whose lease expired while it was working has had its job taken by someone
     * else, and must not write over what the new owner is doing. Its own work is not wasted:
     * generation is idempotent, so whatever it created is what the new owner finds already
     * there and skips.
     *
     * <p>The attempt is compared as well as the replica name because the name alone does not
     * identify a claim: the new owner can be this very replica, having re-claimed the job
     * after the lease lapsed, and then the name matches while the run does not. Every claim
     * increments the attempt, so it is the part that cannot collide.
     */
    private boolean stillOurs(ComplianceGenerationJob job, UUID jobId, String nodeId, int attempt) {
        boolean ours = job.getStatus() == ComplianceJobStatus.RUNNING
                && nodeId.equals(job.getClaimedBy())
                && job.getAttempt() == attempt;
        if (!ours) {
            log.warn("Compliance generation job {} is no longer claimed by {} on attempt {} "
                            + "(status {}, holder {}, attempt {}); leaving it to its current owner",
                    jobId, nodeId, attempt, job.getStatus(), job.getClaimedBy(), job.getAttempt());
        }
        return ours;
    }

    /** Keeps a long provider message inside the column rather than failing the write that reports it. */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 997) + "...";
    }
}
