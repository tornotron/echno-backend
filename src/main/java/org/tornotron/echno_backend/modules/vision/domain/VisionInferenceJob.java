package org.tornotron.echno_backend.modules.vision.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One model version run on one image, the row the vision worker polls. Inserted QUEUED by
 * {@code VisionJobQueue} with a native statement that skips a pending duplicate, so this entity is
 * for reads and for the ingestion stamp. Column names and the claim statement are the contract in
 * {@code docs/specs/2026-11-08-vision-inference-job-contract.md}; renaming one breaks the worker.
 */
@Entity
@Table(name = "cv_inference_jobs")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class VisionInferenceJob implements TenantScopedEntity {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "attachment_id", nullable = false)
    private Long attachmentId;

    @Column(name = "inspection_id")
    private UUID inspectionId;

    @Column(name = "observation_id")
    private UUID observationId;

    @Column(name = "source_key", nullable = false, length = 500)
    private String sourceKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "model_version_id", nullable = false)
    private UUID modelVersionId;

    @Column(name = "model_key", nullable = false, length = 50)
    private String modelKey;

    @Column(name = "model_version", nullable = false, length = 50)
    private String modelVersion;

    @Column(name = "weights_uri", nullable = false, length = 500)
    private String weightsUri;

    @Column(name = "weights_sha256", nullable = false, length = 64)
    private String weightsSha256;

    @Column(name = "input_size", nullable = false)
    private int inputSize;

    /** The version's classes and thresholds when the job was queued. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<CvModelClass> classes;

    @Column(name = "output_key", nullable = false, length = 500)
    private String outputKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_trigger", nullable = false, length = 20)
    private VisionJobTrigger trigger;

    @Column(name = "requested_by_id")
    private Long requestedById;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VisionJobStatus status;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "detection_count")
    private Integer detectionCount;

    @Column(name = "inference_ms")
    private Integer inferenceMs;

    @Column(name = "worker_version", length = 50)
    private String workerVersion;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "queued_at", nullable = false)
    private LocalDateTime queuedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "ingested_at")
    private LocalDateTime ingestedAt;

    @Column(name = "observation_count")
    private Integer observationCount;

    /** Why ingestion stored fewer observations than the worker found, or could not read the output. */
    @Column(name = "ingest_error", columnDefinition = "TEXT")
    private String ingestError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
