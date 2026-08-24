package org.tornotron.echno_backend.material.threshold;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.storageLocation.StorageLocation;

import java.time.LocalDateTime;

/**
 * A per-location override of a material's planning thresholds.
 *
 * <p>The thresholds on {@link Material} (minimum order quantity, reorder level, and safety,
 * minimum and maximum stock) apply globally to the material. When a specific storage location
 * needs different levels, an override is recorded here for that material and location; any field
 * left null falls back to the material's global level. At most one override exists per
 * material and storage location.
 */
@Entity
@NoArgsConstructor
@Data
@Table(
        name = "material_location_threshold",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_material_location_threshold",
                columnNames = {"material_id", "storage_location_id"}
        )
)
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class MaterialLocationThreshold implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_location_id", nullable = false)
    private StorageLocation storageLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column(name = "min_stock")
    private Double minStock;

    @Column(name = "max_stock")
    private Double maxStock;

    @Column(name = "safety_stock")
    private Double safetyStock;

    @Column(name = "reorder_level")
    private Double reorderLevel;

    @Column(name = "moq")
    private Double moq;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
