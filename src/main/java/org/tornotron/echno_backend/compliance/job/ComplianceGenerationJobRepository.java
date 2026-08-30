package org.tornotron.echno_backend.compliance.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes over {@link ComplianceGenerationJob}.
 *
 * <h2>Two kinds of query, and why they are written differently</h2>
 *
 * <p>The caller-facing queries are JPQL and rely on {@code orgFilter} being enabled by the
 * transaction the service opens, exactly like every other tenant-scoped repository here.
 * They also name the organization explicitly, so a path that somehow reached them without a
 * tenant context would still return nothing rather than another tenant's rows. Note the
 * house rule this follows: never {@code findById} on a tenant entity, because the filter
 * does not apply to a load by primary key.
 *
 * <p>The dispatcher's queries are native and deliberately unscoped, because a poller cannot
 * be scoped: it has to see queued work across every tenant to find out which tenant to
 * become. Making them native rather than reaching for the bypass flag is what keeps that
 * blast radius small. A Hibernate {@code @Filter} is not applied to native SQL at all, so
 * the intent is in the query itself rather than in ambient thread state that a later edit
 * could widen by accident. Each returns bare scalars, never a managed entity, so
 * {@code TenantIsolationLoadListener} has nothing to check and no entity crosses a tenant
 * boundary. The organization id comes back as data, and the worker then runs the actual
 * work under it through {@code TenantScopedJobRunner}.
 */
@Repository
public interface ComplianceGenerationJobRepository extends JpaRepository<ComplianceGenerationJob, UUID> {

    @Query("SELECT j FROM ComplianceGenerationJob j "
            + "WHERE j.id = :id AND j.organization.id = :orgId")
    Optional<ComplianceGenerationJob> findByIdScoped(@Param("id") UUID id, @Param("orgId") Long orgId);

    /**
     * The job that currently owns the project, if there is one. At most one row can match,
     * because the partial unique index in changelog {@code 062} allows only one non-terminal
     * row per project.
     */
    @Query("SELECT j FROM ComplianceGenerationJob j "
            + "WHERE j.organization.id = :orgId AND j.projectId = :projectId "
            + "AND j.status IN (org.tornotron.echno_backend.compliance.job.ComplianceJobStatus.QUEUED, "
            + "org.tornotron.echno_backend.compliance.job.ComplianceJobStatus.RUNNING)")
    Optional<ComplianceGenerationJob> findActiveForProject(@Param("orgId") Long orgId,
                                                           @Param("projectId") Long projectId);

    /** The newest job for a project whatever its state, so a reloaded page can find what it was watching. */
    @Query("SELECT j FROM ComplianceGenerationJob j "
            + "WHERE j.organization.id = :orgId AND j.projectId = :projectId "
            + "ORDER BY j.createdAt DESC, j.id DESC")
    List<ComplianceGenerationJob> findLatestForProject(@Param("orgId") Long orgId,
                                                       @Param("projectId") Long projectId,
                                                       org.springframework.data.domain.Pageable pageable);

    // ---------------------------------------------------------------------------------
    // Dispatcher queries. Cross-tenant by necessity, scalars only, never entities.
    // ---------------------------------------------------------------------------------

    /**
     * Ids of the oldest queued jobs, across every tenant. Only a shortlist is read; the
     * claim below is what decides who actually gets each one, so two replicas reading the
     * same shortlist is expected and harmless.
     */
    @Query(value = "SELECT id FROM compliance_generation_jobs "
            + "WHERE status = 'QUEUED' ORDER BY created_at LIMIT :limit", nativeQuery = true)
    List<UUID> findQueuedIds(@Param("limit") int limit);

    /**
     * Takes a queued job for this replica, or does not.
     *
     * <p>This is the whole of the mutual exclusion. The {@code AND status = 'QUEUED'} makes
     * the update a compare-and-set: whichever replica's statement lands first moves the row
     * out of {@code QUEUED} and every other replica's statement then matches no row and
     * returns zero. No leader election, no advisory lock (CockroachDB has none) and no
     * {@code SKIP LOCKED} is needed for that, and none of them would be as easy to reason
     * about. The caller proceeds only on a return of 1.
     *
     * <p>The attempt counter is incremented here rather than by the worker so that an attempt
     * which dies before it can write anything still counts against {@code max_attempts}. A
     * job that crashes the worker every time therefore gives up rather than cycling forever.
     */
    @Modifying
    @Query(value = "UPDATE compliance_generation_jobs SET "
            + "status = 'RUNNING', "
            + "attempt = attempt + 1, "
            + "claimed_by = :claimedBy, "
            + "lease_expires_at = :leaseExpiresAt, "
            + "started_at = COALESCE(started_at, :now), "
            + "updated_at = :now "
            + "WHERE id = :id AND status = 'QUEUED'", nativeQuery = true)
    int claim(@Param("id") UUID id,
              @Param("claimedBy") String claimedBy,
              @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
              @Param("now") LocalDateTime now);

    /**
     * Pushes the claim forward and records how far the run has got. Conditional on the row
     * still being this worker's: a worker whose lease expired and was taken back must not
     * overwrite the progress of whoever picked the job up after it.
     *
     * <p>The condition names the attempt as well as the replica, because the replica name is
     * not a per-claim token: a job whose lease expired can be requeued and then re-claimed by
     * the same replica, and the first, superseded run would otherwise still match on
     * {@code claimed_by} alone. The attempt is incremented by every claim, so it is the value
     * that tells the two runs apart.
     */
    @Modifying
    @Query(value = "UPDATE compliance_generation_jobs SET "
            + "batches_done = :batchesDone, "
            + "rules_assessed = :rulesAssessed, "
            + "lease_expires_at = :leaseExpiresAt, "
            + "updated_at = :now "
            + "WHERE id = :id AND status = 'RUNNING' AND claimed_by = :claimedBy "
            + "AND attempt = :attempt", nativeQuery = true)
    int recordProgress(@Param("id") UUID id,
                       @Param("claimedBy") String claimedBy,
                       @Param("attempt") int attempt,
                       @Param("batchesDone") int batchesDone,
                       @Param("rulesAssessed") int rulesAssessed,
                       @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                       @Param("now") LocalDateTime now);

    /**
     * Returns jobs whose worker stopped extending the lease to the queue.
     *
     * <p>A pod that is killed mid-run leaves its row saying {@code RUNNING} for ever
     * otherwise, and the user watching it would wait for a worker that no longer exists.
     * The attempt counter is not touched here, because the attempt was already counted when
     * it was claimed.
     */
    @Modifying
    @Query(value = "UPDATE compliance_generation_jobs SET "
            + "status = 'QUEUED', "
            + "lease_expires_at = NULL, "
            + "claimed_by = NULL, "
            + "error_message = :message, "
            + "updated_at = :now "
            + "WHERE status = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at < :now "
            + "AND attempt < max_attempts", nativeQuery = true)
    int requeueExpiredLeases(@Param("message") String message, @Param("now") LocalDateTime now);

    /**
     * Fails a job whose worker died and which has no attempts left. Separate from the requeue
     * above so an exhausted job reaches a terminal state instead of sitting {@code RUNNING}
     * behind a lease nothing will ever extend.
     */
    @Modifying
    @Query(value = "UPDATE compliance_generation_jobs SET "
            + "status = 'FAILED', "
            + "lease_expires_at = NULL, "
            + "finished_at = :now, "
            + "error_message = :message, "
            + "updated_at = :now "
            + "WHERE status = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at < :now "
            + "AND attempt >= max_attempts", nativeQuery = true)
    int failExpiredLeasesOutOfAttempts(@Param("message") String message, @Param("now") LocalDateTime now);

    /**
     * The tenant and project a claimed job belongs to, plus the counters the worker needs.
     * Scalars only, so no tenant-scoped entity is loaded on a thread that has no tenant.
     *
     * <p>The aliases are double-quoted because CockroachDB folds an unquoted identifier to
     * lower case, and the projection is matched to the result labels by name.
     */
    @Query(value = "SELECT organization_id AS \"organizationId\", "
            + "project_id AS \"projectId\", "
            + "attempt AS \"attempt\", "
            + "max_attempts AS \"maxAttempts\", "
            + "rules_total AS \"rulesTotal\", "
            + "batches_total AS \"batchesTotal\" "
            + "FROM compliance_generation_jobs WHERE id = :id", nativeQuery = true)
    Optional<DispatchRow> findDispatchRow(@Param("id") UUID id);

    /**
     * Approved projects across every tenant, each with what the sweep needs to decide whether
     * it is still up to date. The nightly sweep's one scan.
     *
     * <p>Cross-tenant and scalars only, for the same reason the dispatcher queries above are:
     * the caller has no tenant, because its whole job is to work out which tenants have
     * something to do. It establishes one per project before anything is enqueued.
     *
     * <h2>The watermark is the run's start, not its finish</h2>
     *
     * <p>{@code lastAssessedAt} is the {@code started_at} of the newest run that succeeded,
     * and the difference from {@code finished_at} is a rule that would otherwise be skipped
     * for ever. A run reads the rule catalogue once, at the top, and then spends minutes in
     * the model. A rule written during those minutes is not in that snapshot and cannot be,
     * but its {@code effective_from} is earlier than the moment the run finished, so a sweep
     * watermarked on {@code finished_at} reads the project as covering a rule no run has ever
     * looked at, on that pass and on every pass after it. Watermarking on the start says only
     * what the run can actually vouch for: everything in force when it began. Anything dated
     * after that is compared honestly, and the cost of the change is at most one extra run per
     * project, which creates nothing because generation is idempotent per rule.
     *
     * <p>The same reasoning covers the retried job. {@code started_at} is written with
     * {@code COALESCE(started_at, ...)} by the claim, so it keeps the first attempt's start
     * across a requeue, and an earlier watermark can only over-trigger.
     *
     * <p>"Assessed" still means a run that reached the model and came back, which is
     * {@code SUCCEEDED} or {@code NOTHING_TO_REPORT}. The second of those matters: a run that
     * assessed every rule and found none applicable did the work, and treating it as
     * never-assessed would put the project back in the queue every night for ever.
     * {@code FAILED} deliberately does not count, so a run that started, spent minutes and
     * then failed marks nothing as assessed; {@code QUEUED} and {@code RUNNING} do not count
     * because they have not finished, and a project with one in flight is filtered out later
     * by the job table's own one-active-job-per-project index, not here.
     *
     * <p>A null {@code lastAssessedAt} is a project that has never had a completed run at all,
     * which is exactly the backlog this exists for: everything approved before generation was
     * wired up, and everything whose approval-time run failed silently.
     *
     * <h2>The jurisdiction that run covered</h2>
     *
     * <p>{@code lastAssessedJurisdiction} comes off the same row as the watermark, so the two
     * describe one run rather than two. Without it, a project assessed under one state and
     * then corrected to another compares its old timestamp against the new state's newest
     * rule, and if that state's rules predate the assessment the project reads as up to date
     * with an empty compliance list, permanently. It is null for every run recorded before the
     * column existed, and the caller reads that null as "not known" rather than as a change.
     *
     * <h2>Ordering is by last attempt, not last success</h2>
     *
     * <p>{@code ORDER BY} runs on the newest terminal run of any kind, failures included,
     * which is not the same as the watermark and is deliberately so. A project that fails
     * every night neither counts as assessed nor blocks its own resubmission, so ordering by
     * success alone leaves it permanently at the front of the queue: twenty-five such projects
     * consume the whole per-run cap every night and nothing behind them is ever reached. Being
     * attempted is enough to move a project down the list, so a pass always reaches new work,
     * and a project that keeps failing is retried once a pass instead of monopolising it.
     */
    @Query(value = "SELECT p.id AS \"projectId\", "
            + "p.organization_id AS \"organizationId\", "
            + "p.project_state AS \"projectState\", "
            + "p.project_address AS \"projectAddress\", "
            + "p.project_type AS \"projectType\", "
            + "a.started_at AS \"lastAssessedAt\", "
            + "a.jurisdiction AS \"lastAssessedJurisdiction\", "
            + "t.last_attempt_at AS \"lastAttemptAt\" "
            + "FROM project p "
            + "LEFT JOIN (SELECT DISTINCT ON (organization_id, project_id) "
            + "                  organization_id, project_id, started_at, jurisdiction "
            + "           FROM compliance_generation_jobs "
            + "           WHERE status IN ('SUCCEEDED', 'NOTHING_TO_REPORT') "
            + "           ORDER BY organization_id, project_id, started_at DESC NULLS LAST) a "
            + "  ON a.project_id = p.id AND a.organization_id = p.organization_id "
            + "LEFT JOIN (SELECT project_id, organization_id, max(finished_at) AS last_attempt_at "
            + "           FROM compliance_generation_jobs "
            + "           WHERE status IN ('SUCCEEDED', 'NOTHING_TO_REPORT', 'FAILED') "
            + "           GROUP BY project_id, organization_id) t "
            + "  ON t.project_id = p.id AND t.organization_id = p.organization_id "
            + "WHERE p.status = 'approved' "
            + "  AND p.organization_id IS NOT NULL "
            + "  AND p.project_type IS NOT NULL "
            + "ORDER BY t.last_attempt_at ASC NULLS FIRST, p.id ASC "
            + "LIMIT :limit", nativeQuery = true)
    List<SweepCandidate> findSweepCandidates(@Param("limit") int limit);

    /**
     * How many generation jobs have been created since a moment, across every tenant.
     *
     * <p>The sweep's per-run cap is a cap on spend, and {@code @Scheduled} has no leader
     * election, so on N replicas the same pass runs N times and a cap counted in memory is
     * really N times the configured number. Counting the rows instead makes the budget one
     * that every replica reads: whichever replica enqueues first is visible to the others
     * before they have got far, and the overshoot falls from a whole cap per replica to
     * roughly one project per replica, since a replica can only overshoot by the submits it
     * makes between two counts. It is not mutual exclusion and does not pretend to be; it is
     * the bound that costs one small count query per submit rather than a coordination
     * protocol.
     *
     * <p>It counts every job created in the window, not only the sweep's own. That is the
     * right reading of a spend cap: a run queued by an approval during the sweep costs the
     * same inference as one the sweep queued.
     */
    @Query(value = "SELECT count(*) FROM compliance_generation_jobs WHERE created_at >= :since",
            nativeQuery = true)
    long countCreatedSince(@Param("since") LocalDateTime since);

    /** One approved project the sweep has to decide about. */
    interface SweepCandidate {
        Long getProjectId();

        Long getOrganizationId();

        String getProjectState();

        String getProjectAddress();

        String getProjectType();

        /**
         * When the newest successful run for this project started, or null if there has never
         * been one. The start rather than the finish; see the query.
         */
        LocalDateTime getLastAssessedAt();

        /**
         * The jurisdiction that run was accepted for, or null if it is not recorded (every run
         * from before the column existed).
         */
        String getLastAssessedJurisdiction();

        /**
         * When the newest terminal run of any kind finished, failures included. Ordering only;
         * it is never compared against the rule catalogue.
         */
        LocalDateTime getLastAttemptAt();
    }

    /** What a worker needs off a claimed row before it can establish the tenant and start. */
    interface DispatchRow {
        Long getOrganizationId();

        Long getProjectId();

        int getAttempt();

        int getMaxAttempts();

        int getRulesTotal();

        int getBatchesTotal();
    }
}
