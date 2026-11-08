package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJob;

@Repository
public interface ParseJobRepository extends JpaRepository<ParseJob, UUID> {

    @Query("SELECT j FROM ParseJob j WHERE j.id = :id")
    Optional<ParseJob> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT j FROM ParseJob j WHERE j.documentId = :documentId ORDER BY j.queuedAt DESC LIMIT 1")
    Optional<ParseJob> findLatestForDocument(@Param("documentId") UUID documentId);

    // ---------------------------------------------------------------------------------
    // Poller queries. Cross-tenant by necessity, scalars only, never entities.
    // ---------------------------------------------------------------------------------

    /** A job id with the organization to pin before it is touched. */
    interface JobRef {
        UUID getId();

        Long getOrganizationId();
    }

    @Query(value = "SELECT id AS id, organization_id AS organizationId FROM doc_ingest_parse_job "
            + "WHERE status = :status AND ingested_at IS NULL ORDER BY finished_at LIMIT :limit", nativeQuery = true)
    List<JobRef> findClosedNotIngested(@Param("status") String status, @Param("limit") int limit);

    /**
     * Takes the ingest claim on a closed job, so of two replicas that listed the same job exactly one
     * gets 1 back; a claim older than {@code staleBefore} belongs to a replica that died and is taken
     * over.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE doc_ingest_parse_job SET ingest_claimed_by = :node, ingest_claimed_at = :now, updated_at = :now "
            + "WHERE id = :id AND organization_id = :organizationId AND status IN ('DONE', 'FAILED') "
            + "AND ingested_at IS NULL AND (ingest_claimed_by IS NULL OR ingest_claimed_at < :staleBefore)",
            nativeQuery = true)
    int claimForIngest(@Param("id") UUID id, @Param("organizationId") Long organizationId, @Param("node") String node,
                       @Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore);

    @Modifying
    @Transactional
    @Query(value = "UPDATE doc_ingest_parse_job SET status = 'QUEUED', worker_id = NULL, lease_expires_at = NULL, "
            + "updated_at = :now WHERE status = 'RUNNING' AND lease_expires_at < :now AND attempt < max_attempts",
            nativeQuery = true)
    int requeueExpiredLeases(@Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query(value = "UPDATE doc_ingest_parse_job SET status = 'FAILED', finished_at = :now, error = :error, "
            + "updated_at = :now WHERE status = 'RUNNING' AND lease_expires_at < :now AND attempt >= max_attempts",
            nativeQuery = true)
    int failExpiredLeases(@Param("now") LocalDateTime now, @Param("error") String error);

    @Query(value = "SELECT DISTINCT organization_id FROM doc_ingest_parse_job WHERE status = 'QUEUED'", nativeQuery = true)
    List<Long> findOrganizationsWithQueuedJobs();

    @Modifying
    @Transactional
    @Query(value = "UPDATE doc_ingest_parse_job SET status = 'FAILED', finished_at = :now, error = :error, "
            + "updated_at = :now WHERE status = 'QUEUED' AND organization_id = :organizationId", nativeQuery = true)
    int failQueuedForOrganization(@Param("organizationId") Long organizationId, @Param("now") LocalDateTime now,
                                  @Param("error") String error);
}
