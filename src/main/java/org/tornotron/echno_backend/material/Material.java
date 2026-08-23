package org.tornotron.echno_backend.material;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumption;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A material item in the catalogue that can be requested, purchased, stocked, and consumed.
 *
 * <p>Carries identity and unit-of-measure (SKU, name, unit, HSN) along with planning
 * levels such as minimum order quantity, reorder level, and safety, minimum, and maximum
 * stock. Stock quantities themselves are not stored here; they live in {@code CurrentStock}
 * and the inventory ledger, which reference this material.
 */
@Entity
@NoArgsConstructor
@Data
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Material implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sku;

    @Column(name = "material_name", nullable = false)
    private String materialName;

    @Column(name = "unit", nullable = false)
    private String unit;

    @Column(name = "description")
    private String description;

    @Column(name = "hsn")
    private String hsn;

    @Column(name = "opening_stock")
    private Double openingStock;

    @Column(name = "moq")
    private Double moq;

    @Column(name = "min_stock")
    private Double minStock;

    @Column(name = "max_stock")
    private Double maxStock;

    @Column(name = "safety_stock")
    private Double safetyStock;

    @Column(name = "reorder_level")
    private Double reorderLevel;

    @OneToMany(mappedBy = "material")
    private List<IndentItem> indentItems;

    @OneToMany(mappedBy = "material")
    private List<GrnItem> grnItems = new ArrayList<>();

    @OneToMany(mappedBy = "material")
    private List<InventoryTransaction> inventoryTransactions = new ArrayList<>();

    @OneToMany(mappedBy = "material")
    private List<MaterialConsumption> materialConsumptions = new ArrayList<>();

    @OneToMany(mappedBy = "material")
    private List<PurchaseOrderItem> purchaseOrderItems = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    private Employee createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
