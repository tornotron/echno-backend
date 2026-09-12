package org.tornotron.echno_backend.modules.bim.repository;

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
import org.tornotron.echno_backend.modules.bim.domain.BimImportJob;

@Repository
public interface BimImportJobRepository extends JpaRepository<BimImportJob, UUID> {

    @Query("SELECT j FROM BimImportJob j WHERE j.id = :id")
    Optional<BimImportJob> findByIdScoped(@Param("id") UUID id);

    List<BimImportJob> findByVersionIdOrderByQueuedAtDesc(UUID versionId);

    Optional<BimImportJob> findFirstByVersionIdOrderByQueuedAtDesc(UUID versionId);

    // ---------------------------------------------------------------------------------
    // Poller queries. Cross-tenant by necessity, scalars only, never entities.
    // ---------------------------------------------------------------------------------

    /** A job id with the organization to pin before it is touched, plus the worker's error. */
    interface JobRef {
        UUID getId();

        Long getOrganizationId();

        String getError();
    }

    @Query(value = "SELECT id AS id, organization_id AS organizationId, error AS error "
            + "FROM bim_import_jobs WHERE status = :status AND ingested_at IS NULL "
            + "ORDER BY finished_at LIMIT :limit", nativeQuery = true)
    List<JobRef> findClosedNotIngested(@Param("status") String status, @Param("limit") int limit);

    @Query(value = "SELECT j.id AS id, j.organization_id AS organizationId, j.error AS error "
            + "FROM bim_import_jobs j JOIN bim_model_versions v ON v.id = j.version_id "
            + "WHERE j.status = 'RUNNING' AND v.status = 'QUEUED'", nativeQuery = true)
    List<JobRef> findRunningWithQueuedVersion();

    @Modifying
    @Transactional
    @Query(value = "UPDATE bim_import_jobs SET status = 'QUEUED', worker_id = NULL, lease_expires_at = NULL, "
            + "updated_at = :now WHERE status = 'RUNNING' AND lease_expires_at < :now AND attempt < max_attempts",
            nativeQuery = true)
    int requeueExpiredLeases(@Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query(value = "UPDATE bim_import_jobs SET status = 'FAILED', finished_at = :now, error = :error, "
            + "updated_at = :now WHERE status = 'RUNNING' AND lease_expires_at < :now AND attempt >= max_attempts",
            nativeQuery = true)
    int failExpiredLeases(@Param("now") LocalDateTime now, @Param("error") String error);
}
