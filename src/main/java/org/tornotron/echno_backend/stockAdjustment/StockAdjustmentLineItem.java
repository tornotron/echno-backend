package org.tornotron.echno_backend.stockAdjustment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.storageLocation.StorageLocation;

import java.math.BigDecimal;

/**
 * A single line within a {@link StockAdjustment} document. Owned by the header through
 * the {@code stock_adjustment_id} FK, which cascades persist and removal. The line also
 * carries its own {@code organization_id} and the {@code orgFilter} so tenant scoping
 * applies to the child rows directly, mirroring the other line-item entities in this
 * codebase (e.g. site-transfer items).
 */
@Entity
@Table(name = "stock_adjustment_line_item")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class StockAdjustmentLineItem implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_adjustment_id", nullable = false)
    private StockAdjustment stockAdjustment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    private Material material;

    @Column(name = "description")
    private String description;

    @Column(name = "system_quantity")
    private Double systemQuantity;

    @Column(name = "physical_quantity")
    private Double physicalQuantity;

    @Column(name = "adjustment_quantity")
    private Double adjustmentQuantity;

    @Column(name = "unit")
    private String unit;

    @Column(name = "unit_value", precision = 15, scale = 2)
    private BigDecimal unitValue;

    @Column(name = "total_adjustment_value", precision = 15, scale = 2)
    private BigDecimal totalAdjustmentValue;

    @Column(name = "reason")
    private String reason;

    @Column(name = "reason_details")
    private String reasonDetails;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private StorageLocation location;

    @Column(name = "bin_location")
    private String binLocation;

    @Column(name = "notes")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
