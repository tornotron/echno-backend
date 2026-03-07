package org.tornotron.echno_backend.vendor;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.vendor.enums.PaymentTermsType;

import java.math.BigDecimal;

@Entity
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class VendorPaymentTerms extends BaseEntity{

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false, unique = true)
    private Vendor vendor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentTermsType paymentTerms;

    private BigDecimal creditLimit;
    private Integer creditDays;
}
