package org.tornotron.echno_backend.vendor;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.converter.AccountNumberConverter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

@Entity
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class VendorBankAccount extends BaseEntity implements TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false)
    private String bankName;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = AccountNumberConverter.class)
    private String accountNumber;

    private String ifscCode;
    private String accountHolderName;
    private String swift;

    @Column(nullable = false)
    private boolean isDefault = false;
}
