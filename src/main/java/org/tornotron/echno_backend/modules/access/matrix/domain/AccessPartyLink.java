package org.tornotron.echno_backend.modules.access.matrix.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * The customer or vendor an outside person represents, for the related scope.
 *
 * <p>Exactly one of the two is set; the factory methods are the only way to make one, and the
 * table's check constraint holds the same rule for anything written another way.
 */
@Entity
@Table(name = "access_party_link")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessPartyLink implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Setter(AccessLevel.NONE)
    @Column(name = "customer_id")
    private UUID customerId;

    @Setter(AccessLevel.NONE)
    @Column(name = "vendor_id")
    private Long vendorId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    public static AccessPartyLink toCustomer(Organization organization, Long employeeId, UUID customerId) {
        AccessPartyLink link = new AccessPartyLink();
        link.organization = organization;
        link.employeeId = employeeId;
        link.customerId = Objects.requireNonNull(customerId, "customerId");
        return link;
    }

    public static AccessPartyLink toVendor(Organization organization, Long employeeId, Long vendorId) {
        AccessPartyLink link = new AccessPartyLink();
        link.organization = organization;
        link.employeeId = employeeId;
        link.vendorId = Objects.requireNonNull(vendorId, "vendorId");
        return link;
    }
}
