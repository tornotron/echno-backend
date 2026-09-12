package org.tornotron.echno_backend.modules.bim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
import org.tornotron.echno_backend.organization.Organization;

/**
 * One IfcProduct of a model, identified across versions by its IFC GlobalId. Matched rows are
 * updated in place on re-import, new ones inserted, missing ones flagged retired and never
 * deleted, so whatever hangs off the element survives a model update.
 *
 * <p>{@code spatialNodeId} is the bridge to the QA/QC hierarchy: the ELEMENT-level
 * {@code SpatialNode} this product became when the hierarchy proposal was confirmed. Null
 * for furniture, annotations, proxies and anything else that is not a construction element.
 */
@Entity
@Table(name = "bim_elements",
        uniqueConstraints = @UniqueConstraint(name = "uq_bim_elements_model_global_id",
                columnNames = {"model_id", "global_id"}),
        indexes = {
                @Index(name = "idx_bim_elements_model_storey", columnList = "model_id, storey_global_id"),
                @Index(name = "idx_bim_elements_spatial_node", columnList = "spatial_node_id"),
                @Index(name = "idx_bim_elements_organization", columnList = "organization_id")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class BimElement implements TenantScopedEntity {

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

    @Column(name = "global_id", nullable = false, length = 100)
    private String globalId;

    @Column(name = "ifc_type", nullable = false, length = 100)
    private String ifcType;

    @Column(length = 300)
    private String name;

    @Column(name = "storey_global_id", length = 100)
    private String storeyGlobalId;

    @Column(name = "space_global_id", length = 100)
    private String spaceGlobalId;

    // {"min":[x,y,z],"max":[x,y,z]} in model coordinates.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bbox", columnDefinition = "jsonb")
    private Map<String, Object> bbox;

    // Property sets flattened by the worker: {"Pset_WallCommon": {"IsExternal": true, ...}}.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "properties", columnDefinition = "jsonb")
    private Map<String, Object> properties;

    @Column(name = "spatial_node_id")
    private UUID spatialNodeId;

    @Column(name = "first_seen_version_id", nullable = false)
    private UUID firstSeenVersionId;

    @Column(name = "last_seen_version_id", nullable = false)
    private UUID lastSeenVersionId;

    @Column(nullable = false)
    private boolean retired = false;

    // Set when a user merged this element into another after an authoring tool regenerated
    // the GlobalId; the survivor carries the associations.
    @Column(name = "merged_into_id")
    private UUID mergedIntoId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
