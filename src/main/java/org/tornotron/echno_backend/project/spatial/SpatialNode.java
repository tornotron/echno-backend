package org.tornotron.echno_backend.project.spatial;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.hibernate.annotations.Filter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.Instant;
import java.util.UUID;

/**
 * One node of a project's site structure: a building, a floor, a zone or a construction
 * element. One table for all four levels so that a consumer needing "any place" holds one
 * nullable {@code spatial_node_id} column and one join.
 *
 * <p>Core, under {@code project}, because inspections, BIM and the robot data layer all
 * reference these ids and none of them should own the others' site structure. Consumers in
 * modules reference the id as a plain column, the way they already hold {@code project_id}.
 *
 * <p>{@code path} is the chain of node ids from the building down to this node, each
 * prefixed by {@code /}, maintained by {@link SpatialNodeService} on create and move.
 * A subtree is every node whose path starts with the root's path. Ids are UUIDs and a node
 * is archived, never deleted, so a reference from an inspection or a capture stays valid.
 */
@Entity
@Table(name = "project_spatial_node",
        uniqueConstraints = @UniqueConstraint(name = "uk_spatial_node_sibling_code",
                columnNames = {"project_id", "parent_id", "code"}),
        indexes = {
                @Index(name = "idx_spatial_node_project_level", columnList = "project_id, level"),
                @Index(name = "idx_spatial_node_project_path", columnList = "project_id, path"),
                @Index(name = "idx_spatial_node_parent", columnList = "parent_id")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
@NoArgsConstructor
public class SpatialNode implements TenantScopedEntity {

    public static final int MAX_DEPTH = SpatialLevel.ELEMENT.depth();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    // Denormalised on every node so a tree can be read and a reference checked without
    // walking up to the building.
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 20)
    private SpatialLevel level;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "path", nullable = false, columnDefinition = "TEXT")
    private String path;

    @Column(name = "depth", nullable = false)
    private int depth;

    // Floors only: negative for basements, 0 for ground.
    @Column(name = "level_index")
    private Integer levelIndex;

    // Elements only. A free slug until the trade catalogue lands its vocabulary.
    @Column(name = "element_type", length = 50)
    private String elementType;

    // IFC GlobalId, unique per project when set (partial unique index in the changeset).
    @Column(name = "bim_element_guid", length = 100)
    private String bimElementGuid;

    // Drawing or grid reference.
    @Column(name = "external_ref", length = 200)
    private String externalRef;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** The path prefix every descendant's path starts with. */
    public String childPathPrefix() {
        return path + "/";
    }
}
