package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJob;

@Repository
public interface ExtractJobRepository extends JpaRepository<ExtractJob, UUID> {

    @Query("SELECT j FROM ExtractJob j WHERE j.id = :id")
    Optional<ExtractJob> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT j FROM ExtractJob j WHERE j.packageId = :packageId ORDER BY j.queuedAt, j.id")
    Page<ExtractJob> findByPackage(@Param("packageId") UUID packageId, Pageable pageable);

    /** One extractor's jobs in a package, latest first, for what it has read. */
    @Query("SELECT j FROM ExtractJob j WHERE j.packageId = :packageId AND j.extractorKey = :key "
            + "ORDER BY j.queuedAt DESC, j.id")
    List<ExtractJob> findByPackageAndKey(@Param("packageId") UUID packageId, @Param("key") String key,
                                         Pageable pageable);

    /** An unfinished job of this extractor on this document and segment, so confirming twice queues one. */
    @Query("SELECT j FROM ExtractJob j WHERE j.documentId = :documentId AND j.extractorKey = :key "
            + "AND ((:segmentId IS NULL AND j.segmentId IS NULL) OR j.segmentId = :segmentId) "
            + "AND j.status IN (org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus.QUEUED, "
            + "org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus.RUNNING, "
            + "org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus.WAITING_LLM, "
            + "org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus.PAUSED_BUDGET)")
    List<ExtractJob> findOpen(@Param("documentId") UUID documentId, @Param("segmentId") UUID segmentId,
                              @Param("key") String key, Pageable pageable);

    /** The latest job of an extractor on a document, segment or not. */
    @Query("SELECT j FROM ExtractJob j WHERE j.documentId = :documentId AND j.extractorKey = :key "
            + "ORDER BY j.queuedAt DESC, j.id")
    List<ExtractJob> findLatest(@Param("documentId") UUID documentId, @Param("key") String key, Pageable pageable);

    /** Jobs of a segment that has been redrawn, still waiting to run; bounded by the caller's page. */
    @Query("SELECT j FROM ExtractJob j WHERE j.segmentId IN :segmentIds "
            + "AND j.status <> org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus.DONE "
            + "AND j.status <> org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus.FAILED")
    List<ExtractJob> findUnfinishedForSegments(@Param("segmentIds") List<UUID> segmentIds, Pageable pageable);

    // ---------------------------------------------------------------------------------
    // Dispatcher queries. Cross-tenant by necessity, scalars only, never entities.
    // ---------------------------------------------------------------------------------

    /** The organization and attempt of a claimed job, to pin the tenant before anything else is read. */
    interface DispatchRow {
        Long getOrganizationId();

        Integer getAttempt();

        Integer getMaxAttempts();
    }

    /** A job id with its organization and, for budget checks, its package. */
    interface WaitingRef {
        UUID getId();

        Long getOrganizationId();

        UUID getPackageId();

        String getStatus();
    }

    @Query(value = "SELECT id FROM doc_ingest_extract_job WHERE status = 'QUEUED' ORDER BY queued_at LIMIT :limit",
            nativeQuery = true)
    List<UUID> findQueuedIds(@Param("limit") int limit);

    /**
     * The whole of the mutual exclusion: whichever replica's statement lands first moves the row out
     * of QUEUED, and every other replica's matches no row. The attempt is counted here, so a job that
     * kills its replica every time still runs out of attempts.
     */
    @Modifying
    @Query(value = "UPDATE doc_ingest_extract_job SET status = 'RUNNING', attempt = attempt + 1, claimed_by = :node, "
            + "lease_expires_at = :leaseUntil, started_at = COALESCE(started_at, :now), message = NULL, updated_at = :now "
            + "WHERE id = :id AND status = 'QUEUED'", nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("node") String node, @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("now") LocalDateTime now);

    @Query(value = "SELECT organization_id AS \"organizationId\", attempt AS \"attempt\", max_attempts AS \"maxAttempts\" "
            + "FROM doc_ingest_extract_job WHERE id = :id", nativeQuery = true)
    Optional<DispatchRow> findDispatchRow(@Param("id") UUID id);

    /**
     * Moves the lease and the count of chunks done, if the claim is still this run's. The attempt is in
     * the condition because the same replica can claim the job again after its lease lapsed.
     */
    @Modifying
    @Query(value = "UPDATE doc_ingest_extract_job SET lease_expires_at = :leaseUntil, chunks_done = :chunksDone, "
            + "updated_at = :now WHERE id = :id AND organization_id = :orgId AND status = 'RUNNING' "
            + "AND claimed_by = :node AND attempt = :attempt", nativeQuery = true)
    int moveLease(@Param("id") UUID id, @Param("orgId") Long orgId, @Param("node") String node,
                  @Param("attempt") int attempt, @Param("chunksDone") int chunksDone,
                  @Param("leaseUntil") LocalDateTime leaseUntil, @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = "UPDATE doc_ingest_extract_job SET status = 'QUEUED', claimed_by = NULL, lease_expires_at = NULL, "
            + "message = :message, updated_at = :now WHERE status = 'RUNNING' AND lease_expires_at IS NOT NULL "
            + "AND lease_expires_at < :now AND attempt < max_attempts", nativeQuery = true)
    int requeueExpiredLeases(@Param("message") String message, @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = "UPDATE doc_ingest_extract_job SET status = 'FAILED', claimed_by = NULL, lease_expires_at = NULL, "
            + "finished_at = :now, message = :message, updated_at = :now WHERE status = 'RUNNING' "
            + "AND lease_expires_at IS NOT NULL AND lease_expires_at < :now AND attempt >= max_attempts",
            nativeQuery = true)
    int failExpiredLeases(@Param("message") String message, @Param("now") LocalDateTime now);

    @Query(value = "SELECT DISTINCT organization_id FROM doc_ingest_extract_job "
            + "WHERE status IN ('QUEUED', 'WAITING_LLM', 'PAUSED_BUDGET')", nativeQuery = true)
    List<Long> findOrganizationsWithQueuedWork();

    @Modifying
    @Query(value = "UPDATE doc_ingest_extract_job SET status = 'FAILED', finished_at = :now, message = :message, "
            + "updated_at = :now WHERE organization_id = :orgId AND status IN ('QUEUED', 'WAITING_LLM', 'PAUSED_BUDGET')",
            nativeQuery = true)
    int failQueuedForOrganization(@Param("orgId") Long orgId, @Param("message") String message,
                                  @Param("now") LocalDateTime now);

    @Query(value = "SELECT id AS \"id\", organization_id AS \"organizationId\", package_id AS \"packageId\", "
            + "status AS \"status\" FROM doc_ingest_extract_job WHERE status IN ('WAITING_LLM', 'PAUSED_BUDGET') "
            + "ORDER BY queued_at LIMIT :limit", nativeQuery = true)
    List<WaitingRef> findWaiting(@Param("limit") int limit);

    /** Puts a waiting job back in the queue once what it waited for has cleared. */
    @Modifying
    @Query(value = "UPDATE doc_ingest_extract_job SET status = 'QUEUED', attempt = 0, message = NULL, updated_at = :now "
            + "WHERE id = :id AND organization_id = :orgId AND status = :from", nativeQuery = true)
    int resume(@Param("id") UUID id, @Param("orgId") Long orgId, @Param("from") String from,
               @Param("now") LocalDateTime now);
}
