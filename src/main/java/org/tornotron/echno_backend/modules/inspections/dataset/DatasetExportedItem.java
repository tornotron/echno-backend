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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The ledger that makes the export idempotent (#791): one row per source object ever copied
 * for an organization, unique on the source reference. A later run reads the set and skips
 * what is already here, so re-running exports nothing new.
 *
 * <p>The source reference is the attachment id for evidence attachments and the stored photo
 * string for defect photos, which is the same string {@code DefectPhotoAnnotation} keys on:
 * a replaced photo is a new reference and is exported as a new image.
 */
@Entity
@Table(name = "dataset_exported_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_dataset_exported_item_source",
                columnNames = {"organization_id", "source_kind", "source_ref"}),
        indexes = {@Index(name = "idx_dataset_exported_item_run", columnList = "run_id")})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class DatasetExportedItem implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 30)
    private DatasetSourceKind sourceKind;

    @Column(name = "source_ref", nullable = false, length = 500)
    private String sourceRef;

    @Column(name = "source_key", nullable = false, length = 500)
    private String sourceKey;

    @Column(name = "export_key", nullable = false, length = 500)
    private String exportKey;

    @Column(name = "inspection_id")
    private UUID inspectionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
