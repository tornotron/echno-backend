package org.tornotron.echno_backend.indentItem;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.indent.Indent;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.material.Material;

import java.util.Arrays;

/**
 * A single material line on an indent.
 *
 * <p>Holds the requested and later ordered quantities for one material plus any
 * specifications. A flag records whether the line has been converted into a purchase
 * order, alongside the linked PO number, so partially procured indents can be tracked.
 */
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

    /** Room in {@code linked_purchase_order_number}, a VARCHAR(100). */
    private static final int LINKED_NUMBER_MAX_LENGTH = 100;

    /**
     * Records that {@code quantity} of this line was ordered on purchase order
     * {@code purchaseOrderNumber}.
     *
     * <p>The first conversion sets the ordered quantity and the linked number, replacing any
     * figure typed on the indent before it was ordered. A later conversion of the same line (a
     * partly ordered line going onto a second PO) adds its quantity and appends its number, so
     * the indent shows everything the line went to. A number that would overflow the column is
     * left off the list rather than failing the purchase order; the quantity is still added.
     *
     * @param purchaseOrderNumber the number of the purchase order the line was put on
     * @param quantity            the quantity ordered on that purchase order
     */
    public void recordConversion(String purchaseOrderNumber, Integer quantity) {
        boolean firstConversion = !Boolean.TRUE.equals(convertedToPurchaseOrder);
        int ordered = quantity != null ? quantity : 0;
        if (firstConversion || orderedQuantity == null) {
            orderedQuantity = ordered;
        } else {
            orderedQuantity = orderedQuantity + ordered;
        }

        if (purchaseOrderNumber != null) {
            if (firstConversion || linkedPurchaseOrderNumber == null || linkedPurchaseOrderNumber.isBlank()) {
                linkedPurchaseOrderNumber = purchaseOrderNumber;
            } else if (!Arrays.asList(linkedPurchaseOrderNumber.split(",\\s*")).contains(purchaseOrderNumber)) {
                String joined = linkedPurchaseOrderNumber + ", " + purchaseOrderNumber;
                if (joined.length() <= LINKED_NUMBER_MAX_LENGTH) {
                    linkedPurchaseOrderNumber = joined;
                }
            }
        }
        convertedToPurchaseOrder = true;
    }
}
