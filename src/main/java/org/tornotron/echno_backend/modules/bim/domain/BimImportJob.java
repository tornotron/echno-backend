package org.tornotron.echno_backend.modules.bim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
import org.tornotron.echno_backend.modules.bim.BimImportJobStatus;
import org.tornotron.echno_backend.organization.Organization;

/**
 * The row the IfcOpenShell worker polls. The backend inserts it QUEUED with the source key
 * and the output prefix; the worker claims it, writes its outputs under the prefix and
 * closes it DONE or FAILED; the backend then ingests the outputs and stamps
 * {@code ingestedAt}. Column names and the claim statement are the contract in
 * {@code docs/specs/2026-09-13-bim-import-contract.md}; renaming one here breaks the worker.
 */
@Entity
@Table(name = "bim_import_jobs",
        indexes = {
                @Index(name = "idx_bim_jobs_status_queued", columnList = "status, queued_at"),
                @Index(name = "idx_bim_jobs_version", columnList = "version_id"),
                @Index(name = "idx_bim_jobs_organization", columnList = "organization_id")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class BimImportJob implements TenantScopedEntity {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "model_id", nullable = false)
    private UUID modelId;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BimImportJobStatus status = BimImportJobStatus.QUEUED;

    @Column(name = "source_key", nullable = false, length = 500)
    private String sourceKey;

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

    @Column(name = "element_count")
    private Integer elementCount;

    @Column(name = "storey_count")
    private Integer storeyCount;

    @Column(name = "worker_version", length = 50)
    private String workerVersion;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "queued_at", nullable = false)
    private LocalDateTime queuedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "ingested_at")
    private LocalDateTime ingestedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void transitionTo(BimImportJobStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("A BIM import job cannot go from " + status + " to " + next);
        }
        this.status = next;
    }

    public boolean hasAttemptsLeft() {
        return attempt < maxAttempts;
    }
}
