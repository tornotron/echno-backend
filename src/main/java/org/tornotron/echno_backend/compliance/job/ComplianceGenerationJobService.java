package org.tornotron.echno_backend.compliance.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.SqlStateDetector;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.compliance.ComplianceGenerationService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accepts compliance generation work and reports on it. The work itself is done later, by
 * {@link ComplianceGenerationJobDispatcher} on whichever replica claims the row.
 *
 * <h2>What "accept" costs</h2>
 *
 * <p>A precondition check and an insert. The preconditions are checked here, on the request
 * thread, on purpose: a project with no type, no recognisable state, no rules for its
 * jurisdiction, or an unconfigured AI service is a fault the user can fix and should be told
 * about immediately, as the 400 or 404 it has always been. Deferring those to the worker
 * would have turned a clear error message into a job the user has to poll before finding out
 * that nothing was ever going to work. Only failures that genuinely need the model, which is
 * to say the ones nobody can predict, come back later as a failed job.
 *
 * <h2>Clicking twice</h2>
 *
 * <p>The second click joins the first run rather than starting a second. That is not
 * politeness: two runs for one project both spend a minute of inference and then race to
 * insert the same inspections, which is the duplicate the inspection-level unique index had
 * to be added to catch. Here the partial unique index on the job table stops the second run
 * from ever starting, and the loser of the insert reads back the winner's row and returns it,
 * so both callers watch one run. The user sees the run they asked for either way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceGenerationJobService {

    private final ComplianceGenerationJobRepository jobRepository;
    private final ComplianceGenerationService complianceGenerationService;
    private final ComplianceJobProperties jobProperties;
    private final TenantEntityHelper tenantEntityHelper;
    private final TransactionRetryTemplate retryTemplate;

    /** What {@link #submit} did, so the controller can answer 202 for new work and 200 for a join. */
    public record Accepted(ComplianceGenerationJobDto job, boolean created) {}

    /**
     * Accepts a generation request for a project, or hands back the run already in flight
     * for it.
     *
     * @throws org.tornotron.echno_backend.common.exception.ResourceNotFoundException no such
     *         project in this tenant
     * @throws org.tornotron.echno_backend.common.exception.InvalidRequestException the project
     *         or the configuration cannot support a run, with the fix in the message
     */
    public Accepted submit(Long projectId, Long orgId) {
        int ruleCount = complianceGenerationService.validateAndCountCandidateRules(projectId, orgId);
        int batches = complianceGenerationService.batchCount(ruleCount);

        Optional<ComplianceGenerationJob> running = retryTemplate.execute(
                "ComplianceGenerationJobService.findActive",
                () -> jobRepository.findActiveForProject(orgId, projectId));
        if (running.isPresent()) {
            return new Accepted(ComplianceGenerationJobDto.from(running.get()), false);
        }

        try {
            ComplianceGenerationJob job = retryTemplate.execute(
                    "ComplianceGenerationJobService.submit",
                    () -> insert(projectId, ruleCount, batches));
            log.info("Accepted compliance generation job {} for project {} in organization {} "
                            + "({} rule(s) over {} batch(es))",
                    job.getId(), projectId, orgId, ruleCount, batches);
            return new Accepted(ComplianceGenerationJobDto.from(job), true);
        } catch (RuntimeException e) {
            if (!SqlStateDetector.carriesSqlState(e, SqlStateDetector.UNIQUE_VIOLATION)) {
                throw e;
            }
            // Another request inserted between the read above and this insert. Its row is the
            // run, so return that rather than reporting a conflict the caller cannot act on.
            return new Accepted(ComplianceGenerationJobDto.from(
                    reloadAfterLostRace(projectId, orgId)), false);
        }
    }

    /** One job, or 404. Scoped to the tenant, so another organization's job id reads as absent. */
    public ComplianceGenerationJobDto find(UUID jobId, Long orgId) {
        ComplianceGenerationJob job = retryTemplate.execute(
                "ComplianceGenerationJobService.find",
                () -> jobRepository.findByIdScoped(jobId, orgId).orElseThrow(() ->
                        new ResourceNotFoundException(
                                "No compliance generation job with id " + jobId
                                        + " was found in this organization.")));
        return ComplianceGenerationJobDto.from(job);
    }

    /**
     * The most recent job for a project, if it has ever had one. This is what a page that was
     * reloaded mid-run polls to find what it was watching, since the job id it held is gone
     * with the tab.
     */
    public Optional<ComplianceGenerationJobDto> findLatestForProject(Long projectId, Long orgId) {
        List<ComplianceGenerationJob> latest = retryTemplate.execute(
                "ComplianceGenerationJobService.findLatest",
                () -> jobRepository.findLatestForProject(orgId, projectId, PageRequest.of(0, 1)));
        return latest.stream().findFirst().map(ComplianceGenerationJobDto::from);
    }

    private ComplianceGenerationJob insert(Long projectId, int ruleCount, int batches) {
        ComplianceGenerationJob job = new ComplianceGenerationJob();
        job.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        job.setProjectId(projectId);
        job.setStatus(ComplianceJobStatus.QUEUED);
        job.setRulesTotal(ruleCount);
        job.setBatchesTotal(batches);
        job.setMaxAttempts(Math.max(1, jobProperties.getMaxAttempts()));
        return jobRepository.save(job);
    }

    /**
     * Reads back the job that won the insert race.
     *
     * <p>It can legitimately be gone by the time we look: a very short run could have finished
     * and left the active state between the constraint rejecting our insert and this read.
     * That is not an error, so the newest job for the project is returned instead, which is
     * the one the caller wanted to know about either way.
     */
    private ComplianceGenerationJob reloadAfterLostRace(Long projectId, Long orgId) {
        return retryTemplate.execute("ComplianceGenerationJobService.reload", () ->
                jobRepository.findActiveForProject(orgId, projectId)
                        .or(() -> jobRepository
                                .findLatestForProject(orgId, projectId, PageRequest.of(0, 1))
                                .stream().findFirst())
                        .orElseThrow(() -> new IllegalStateException(
                                "A compliance generation job for project " + projectId
                                        + " was rejected as a duplicate but no job for that project "
                                        + "can be found")));
    }
}
