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
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.vendor.Vendor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Entity
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class GoodsReceivedNote implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grn_number", nullable = false, unique = true)
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
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
