package org.tornotron.echno_backend.vendor;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.vendor.enums.PaymentTermsType;

import java.math.BigDecimal;

@Entity
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class VendorPaymentTerms extends BaseEntity implements TenantScopedEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false, unique = true)
    private Vendor vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentTermsType paymentTerms;

    private BigDecimal creditLimit;
    private Integer creditDays;
}
