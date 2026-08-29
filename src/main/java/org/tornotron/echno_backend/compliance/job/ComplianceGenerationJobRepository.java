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
