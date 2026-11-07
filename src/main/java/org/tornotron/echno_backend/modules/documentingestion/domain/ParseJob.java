package org.tornotron.echno_backend.modules.documentingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * The row the ingest worker polls. The backend inserts it QUEUED with the source key and the output
 * prefix; the worker claims it, writes its outputs and closes it; the backend then reads the outputs
 * and stamps {@code ingestedAt}. Column names and the claim statement are the contract in
 * {@code docs/specs/2026-11-08-ingest-worker-contract.md}; renaming one here breaks the worker.
 */
@Entity
@Table(name = "doc_ingest_parse_job")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ParseJob implements TenantScopedEntity {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 10)
    private ParseJobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParseJobStatus status = ParseJobStatus.QUEUED;

    @Column(name = "source_key", nullable = false, length = 500)
    private String sourceKey;

    @Column(name = "source_file_name", nullable = false, length = 300)
    private String sourceFileName;

    @Column(name = "output_prefix", nullable = false, length = 500)
    private String outputPrefix;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Column(nullable = false)
    private int attempt = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "source_sha256", length = 64)
    private String sourceSha256;

    @Column(name = "source_size_bytes")
    private Long sourceSizeBytes;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "sheet_count")
    private Integer sheetCount;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "pages_ocr")
    private Integer pagesOcr;

    @Column(name = "pages_failed")
    private Integer pagesFailed;

    @Column(name = "reused_output_prefix", length = 500)
    private String reusedOutputPrefix;

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

    @Column(name = "ingest_claimed_by", length = 100)
    private String ingestClaimedBy;

    @Column(name = "ingest_claimed_at")
    private LocalDateTime ingestClaimedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
