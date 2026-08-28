package org.tornotron.echno_backend.goodsReceivedNote;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.vendor.Vendor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Records a delivery of goods received against a purchase order at a project site.
 *
 * <p>A GRN captures what a vendor actually delivered (per-material quantities and unit
 * costs held in its {@link GrnItem} lines), who received it, and where. Creating one
 * raises the stock of the received materials through an inventory transaction and can
 * later drive payables to the vendor.
 */
@Data
@NoArgsConstructor
@Entity
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class GoodsReceivedNote implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Allocated by the server, never by the client. Unique per organization rather than
     * globally: the constraint that enforces it is {@code uk_grn_org_number} in the schema, and it
     * is composite, so it cannot be declared here as a column-level unique.
     */
    @Column(name = "grn_number", nullable = false)
    private String grnNumber;

    @Column(name = "received_on", nullable = false)
    private LocalDateTime receivedOn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee receivedBy;

    @ManyToOne
    private Vendor vendor;

    @Column(name = "delivery_challan_number")
    private String deliveryChallanNumber;

    @Column(name = "invoice_number")
    private String invoiceNumber;

    @Column(name = "invoice_amount")
    private Double invoiceAmount;

    @OneToMany(mappedBy = "goodsReceivedNote")
    private List<GrnItem> items = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    @OneToMany(mappedBy = "goodsReceivedNote")
    private List<Payable> payables = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_location_id")
    private StorageLocation storageLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
