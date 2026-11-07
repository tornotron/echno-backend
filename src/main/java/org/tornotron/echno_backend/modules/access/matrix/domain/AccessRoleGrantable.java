package org.tornotron.echno_backend.modules.access.matrix.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One role a role's holders may assign to others.
 *
 * <p>A row of its own, with its organization, because the database forces both roles into that
 * organization, which a plain join table between two roles cannot do.
 */
@Entity
@Table(name = "access_role_grantable",
        uniqueConstraints = @UniqueConstraint(name = "uq_access_role_grantable",
                columnNames = {"role_id", "grantable_role_id"}))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class AccessRoleGrantable implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private AccessRole role;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "grantable_role_id", nullable = false)
    private AccessRole grantableRole;
}
