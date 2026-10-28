package org.tornotron.echno_backend.modules.access.matrix.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * A department or group of people in one organization.
 *
 * <p>Its members hold every role it lists, plus its own grants. Members are employee ids, the
 * person's membership in the organization, kept as a plain collection rather than an
 * association, so the module reads nothing of the employee it does not need.
 */
@Entity
@Table(name = "access_group",
        uniqueConstraints = @UniqueConstraint(name = "uq_access_group_name", columnNames = {"organization_id", "name"}))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class AccessGroup implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessGroupKind kind = AccessGroupKind.GROUP;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "access_group_member", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "employee_id", nullable = false)
    private Set<Long> memberIds = new HashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "access_group_role",
            joinColumns = @JoinColumn(name = "group_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<AccessRole> roles = new HashSet<>();

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<AccessGroupGrant> grants = new ArrayList<>();

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

    /** Adds a grant unless the group already holds that permission with that scope. */
    public void grant(AccessPermission permission, AccessScope scope) {
        String key = permission.key();
        boolean present = grants.stream()
                .anyMatch(g -> g.getPermissionKey().equals(key) && g.getScope() == scope);
        if (present) {
            return;
        }
        AccessGroupGrant grant = new AccessGroupGrant();
        grant.setGroup(this);
        grant.setPermissionKey(key);
        grant.setScope(scope);
        grants.add(grant);
    }

    public boolean revoke(AccessPermission permission, AccessScope scope) {
        String key = permission.key();
        return grants.removeIf(g -> g.getPermissionKey().equals(key) && g.getScope() == scope);
    }
}
