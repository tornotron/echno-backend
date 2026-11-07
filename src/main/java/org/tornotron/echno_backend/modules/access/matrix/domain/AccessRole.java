package org.tornotron.echno_backend.modules.access.matrix.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.organization.Organization;

/**
 * A role in one organization, and what it grants.
 *
 * <p>Tenant scoped behind {@code orgFilter}; the grants are an owned child. Only the System Admin
 * and the baseline role are system roles; every other role can be renamed, edited and deleted by
 * the organization. The baseline role is the one every active employee holds. A role copied from a
 * template with a counterpart in the fixed org roles records it in {@link #legacyOrgRole}.
 */
@Entity
@Table(name = "access_role",
        uniqueConstraints = @UniqueConstraint(name = "uq_access_role_slug", columnNames = {"organization_id", "slug"}))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class AccessRole implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    // Stable machine name, unique in the organization; a copied role keeps its template's.
    @Column(nullable = false, length = 80)
    private String slug;

    // Unique in the organization ignoring case, through an index in the changelog.
    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 120)
    private String subtitle;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(name = "is_baseline", nullable = false)
    private boolean baseline;

    // The fixed org role this role stands in for, such as "project-manager", while endpoints
    // still check role names. Null for roles an organization creates.
    @Column(name = "legacy_org_role", length = 60)
    private String legacyOrgRole;

    @OneToMany(mappedBy = "role", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<AccessRoleGrant> grants = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    /** Adds a grant unless the role already holds that permission with that scope. */
    public void grant(AccessPermission permission, AccessScope scope) {
        String key = permission.key();
        boolean present = grants.stream()
                .anyMatch(g -> g.getPermissionKey().equals(key) && g.getScope() == scope);
        if (present) {
            return;
        }
        AccessRoleGrant grant = new AccessRoleGrant();
        grant.setRole(this);
        grant.setPermissionKey(key);
        grant.setScope(scope);
        grants.add(grant);
    }

    /** Removes a grant; answers whether the role held it. */
    public boolean revoke(AccessPermission permission, AccessScope scope) {
        String key = permission.key();
        return grants.removeIf(g -> g.getPermissionKey().equals(key) && g.getScope() == scope);
    }
}
