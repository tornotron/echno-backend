package org.tornotron.echno_backend.material;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumption;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;

import java.util.ArrayList;
import java.util.List;

@Entity
@NoArgsConstructor
@Data
@FilterDef(name = "orgFilter", parameters = @ParamDef(name = "organizationId", type = Long.class))
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
}
