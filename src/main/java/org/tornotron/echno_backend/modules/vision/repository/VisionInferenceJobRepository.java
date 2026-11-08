package org.tornotron.echno_backend.modules.vision.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.vision.domain.VisionInferenceJob;

public interface VisionInferenceJobRepository extends JpaRepository<VisionInferenceJob, UUID> {

    @Query("SELECT COUNT(j) > 0 FROM VisionInferenceJob j WHERE j.attachmentId = :attachmentId "
            + "AND j.modelVersionId = :modelVersionId AND j.status = org.tornotron.echno_backend.modules.vision.domain.VisionJobStatus.DONE")
    boolean existsDoneScoped(@Param("attachmentId") Long attachmentId, @Param("modelVersionId") UUID modelVersionId);

    @Query("SELECT j FROM VisionInferenceJob j WHERE j.attachmentId = :attachmentId ORDER BY j.queuedAt")
    List<VisionInferenceJob> findByAttachmentScoped(@Param("attachmentId") Long attachmentId);

    // ---------------------------------------------------------------------------------
    // Poller queries. Cross-tenant by necessity, scalars only, never entities.
    // ---------------------------------------------------------------------------------

    /** A job id with the organization to pin before it is touched. */
    interface JobRef {
        UUID getId();

        Long getOrganizationId();
    }

    @Query(value = "SELECT id AS id, organization_id AS organizationId FROM cv_inference_jobs "
            + "WHERE status = 'DONE' AND ingested_at IS NULL ORDER BY finished_at LIMIT :limit", nativeQuery = true)
    List<JobRef> findDoneNotIngested(@Param("limit") int limit);

    @Modifying
    @Transactional
    @Query(value = "UPDATE cv_inference_jobs SET status = 'QUEUED', worker_id = NULL, lease_expires_at = NULL, "
            + "updated_at = :now WHERE status = 'RUNNING' AND lease_expires_at < :now AND attempt < max_attempts",
            nativeQuery = true)
    int requeueExpiredLeases(@Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query(value = "UPDATE cv_inference_jobs SET status = 'FAILED', finished_at = :now, error = :error, "
            + "updated_at = :now WHERE status = 'RUNNING' AND lease_expires_at < :now AND attempt >= max_attempts",
            nativeQuery = true)
    int failExpiredLeases(@Param("now") LocalDateTime now, @Param("error") String error);
}
