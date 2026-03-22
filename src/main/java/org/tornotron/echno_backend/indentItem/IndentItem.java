package org.tornotron.echno_backend.indentItem;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.indent.Indent;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.material.Material;

@Data
@NoArgsConstructor
@Entity
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class IndentItem implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Indent indent;

    @ManyToOne
    private Material material;

    @Column(name = "additional_specifications")
    private String additionalSpecifications;

    @Column(name = "requested_quantity", nullable = false)
    private Integer requestedQuantity;

    @Column(name = "ordered_quantity")
    private Integer orderedQuantity;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "converted_to_purchase_order", nullable = false)
    private Boolean convertedToPurchaseOrder;

    @Column(name = "linked_purchase_order_number")
    private String linkedPurchaseOrderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

}
