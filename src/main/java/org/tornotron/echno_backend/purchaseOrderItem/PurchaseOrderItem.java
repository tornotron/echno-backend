package org.tornotron.echno_backend.purchaseOrderItem;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;

import java.math.BigDecimal;

/**
 * A single material line on a purchase order.
 *
 * <p>Holds the ordered quantity and unit price for one material, the running received
 * quantity as deliveries arrive, and the line total. May trace back to the indent item
 * it was raised from.
 */
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class PurchaseOrderItem implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @ManyToOne
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @ManyToOne
    @JoinColumn(name = "indent_item_id")
    private IndentItem indentItem;

    @Column(name = "ordered_quantity", nullable = false)
    private Integer orderedQuantity;

    @Column(name = "received_quantity", nullable = false)
    private Integer receivedQuantity = 0;

    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "total_price", precision = 15, scale = 2)
    private BigDecimal totalPrice;

    @Column(name = "remarks")
    private String remarks;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
