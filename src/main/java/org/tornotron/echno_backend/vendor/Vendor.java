package org.tornotron.echno_backend.vendor;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.vendor.enums.VendorStatus;
import org.tornotron.echno_backend.vendor.enums.VendorType;

import java.util.ArrayList;
import java.util.List;


@Entity
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Vendor extends BaseEntity implements TenantScopedEntity {

    @Column(name = "vendor_name", nullable = false)
    private String vendorName;

    @Column(name = "vendor_address", nullable = true)
    private String vendorAddress;

    private String city;

    private String state;

    private String pinCode;

    private String country;

    private String website;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VendorType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VendorStatus status;

    @Column(length = 1000)
    private String notes;

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

    @OneToMany(mappedBy = "vendor", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VendorContact> contacts = new ArrayList<>();

    @OneToMany(mappedBy = "vendor", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VendorTaxIdentifier> taxIdentifiers = new ArrayList<>();

    @OneToOne(mappedBy = "vendor", cascade = CascadeType.ALL, orphanRemoval = true)
    private VendorPaymentTerms paymentTerms;

    @OneToMany(mappedBy = "vendor", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VendorBankAccount> bankAccounts = new ArrayList<>();

    public void addContact(VendorContact contact) {
        contacts.add(contact);
        contact.setVendor(this);
    }

    public void addTaxIdentifier(VendorTaxIdentifier taxId) {
        taxIdentifiers.add(taxId);
        taxId.setVendor(this);
    }

    public void addBankAccount(VendorBankAccount account) {
        bankAccounts.add(account);
        account.setVendor(this);
    }

    public void setPaymentTerms(VendorPaymentTerms terms) {
        if (terms != null) {
            terms.setVendor(this);
        }
        this.paymentTerms = terms;
    }
}
