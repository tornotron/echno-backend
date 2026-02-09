package org.tornotron.echno_backend.vendor;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;

import java.util.ArrayList;
import java.util.List;


@Entity
@NoArgsConstructor
@Data
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Vendor implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vendor_name", nullable = false)
    private String vendorName;

    @Column(name = "vendor_address", nullable = true)
    private String vendorAddress;

    @Column(name = "vendor_email", nullable = false)
    private String vendorEmail;

    @OneToMany(mappedBy = "vendor")
    private List<GoodsReceivedNote> goodsReceivedNotes = new ArrayList<>();

    @OneToMany(mappedBy = "vendor")
    private List<PurchaseOrder> purchaseOrders = new ArrayList<>();

    @OneToMany(mappedBy = "vendor")
    private List<Payable> payables = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
