package org.tornotron.echno_backend.modules.inspections.dataset;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One export run for one organization (#791): what was copied, where the manifest is, and
 * how it ended. The run key is the {@code export/<runKey>/} segment in the dataset bucket.
 */
@Entity
@Table(name = "dataset_export_runs",
        indexes = {@Index(name = "idx_dataset_export_run_org", columnList = "organization_id, created_at")})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class DatasetExportRun implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "run_key", nullable = false, length = 64, unique = true)
    private String runKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DatasetExportRunStatus status = DatasetExportRunStatus.RUNNING;

    /** {@code schedule}, or {@code user:<id>} for an on-demand run. */
    @Column(name = "triggered_by", nullable = false, length = 120)
    private String triggeredBy;

    @Column(name = "exported_count", nullable = false)
    private int exportedCount;

    /** Objects already exported by an earlier run and therefore left alone. */
    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "manifest_key", length = 500)
    private String manifestKey;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
