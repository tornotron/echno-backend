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
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.modules.bim.BimVersionStatus;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One uploaded IFC of a model. The source file, the worker's outputs and the tiles all live
 * under this version's object-store prefix; the status says how far it got. The hierarchy
 * proposal the worker's structure tree yields is kept here until a user confirms it.
 */
@Entity
@Table(name = "bim_model_versions",
        indexes = {
                @Index(name = "idx_bim_versions_model", columnList = "model_id, version_number"),
                @Index(name = "idx_bim_versions_organization", columnList = "organization_id")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class BimModelVersion implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "model_id", nullable = false)
    private UUID modelId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BimVersionStatus status = BimVersionStatus.UPLOADED;

    @Column(name = "source_key", nullable = false, length = 500)
    private String sourceKey;

    @Column(name = "source_filename", length = 300)
    private String sourceFilename;

    @Column(name = "source_size_bytes")
    private Long sourceSizeBytes;

    @Column(name = "ifc_schema", length = 20)
    private String ifcSchema;

    @Column(name = "element_count")
    private Integer elementCount;

    @Column(name = "storey_count")
    private Integer storeyCount;

    // model-meta.json as the worker wrote it: units, site placement, true north, storeys.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta", columnDefinition = "jsonb")
    private Map<String, Object> meta;

    // The Building > Floor > Zone > Element tree proposed from structure.json, held until
    // confirmed. Null before ingestion; after confirmation the proposal stays for audit.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hierarchy_proposal", columnDefinition = "jsonb")
    private Map<String, Object> hierarchyProposal;

    @Column(name = "hierarchy_confirmed_at")
    private LocalDateTime hierarchyConfirmedAt;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "uploaded_by_id")
    private Long uploadedById;

    @Column(name = "imported_at")
    private LocalDateTime importedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void transitionTo(BimVersionStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("A BIM model version cannot go from " + status + " to " + next);
        }
        this.status = next;
    }
}
