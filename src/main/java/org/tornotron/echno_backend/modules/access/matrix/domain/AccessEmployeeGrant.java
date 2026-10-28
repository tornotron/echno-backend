package org.tornotron.echno_backend.modules.access.matrix.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.organization.Organization;

/**
 * A permission granted to one person directly, on top of what their roles and groups give.
 * Grants only add: there is no grant that takes access away.
 */
@Entity
@Table(name = "access_employee_grant",
        uniqueConstraints = @UniqueConstraint(name = "uq_access_employee_grant",
                columnNames = {"employee_id", "permission_key", "scope"}))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class AccessEmployeeGrant implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "permission_key", nullable = false, length = 120)
    private String permissionKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessScope scope;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;
}
