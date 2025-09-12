package org.tornotron.echno_backend.material;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumption;

import java.util.ArrayList;
import java.util.List;

@Entity
@NoArgsConstructor
@Data
public class Material {

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
}
