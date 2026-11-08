package org.tornotron.echno_backend.role;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.organization.Organization;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One organization's definition of a role: a custom role it made, or its changes to a built-in one.
 *
 * <p><b>Custom role</b> ({@link #systemRole} null): a name, a description, and the permissions it
 * grants, every entry in {@link #permissions} being {@code true}. Held by the employees in
 * {@link #members}.
 *
 * <p><b>Built-in role</b> ({@link #systemRole} set): only the organization's overrides of the code
 * defaults in {@link DefaultRolePermissions}, {@code true} to add and {@code false} to take away.
 * The row exists only once the organization has changed the role; with no row the defaults apply.
 * Who holds a built-in role is still recorded on the employee ({@code employee_org_roles}), so
 * {@link #members} is always empty here.
 *
 * <p>Tenant scoped behind {@code orgFilter}. Deleting the organization deletes its roles, and
 * deleting a role or an employee deletes the assignments between them (database cascades).
 */
@Entity
@Table(name = "org_role", uniqueConstraints = {
        @UniqueConstraint(name = "uq_org_role_name", columnNames = {"organization_id", "name"}),
        @UniqueConstraint(name = "uq_org_role_system_role", columnNames = {"organization_id", "system_role"})
})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class RoleDefinition implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /** The built-in role this row changes, or null for a custom role. */
    @Enumerated(EnumType.STRING)
    @Column(name = "system_role", length = 64)
    private OrgRole systemRole;

    /** Permission code to granted. See the class comment for what {@code false} means. */
    @ElementCollection
    @CollectionTable(name = "org_role_permission", joinColumns = @JoinColumn(name = "org_role_id"))
    @MapKeyColumn(name = "permission", length = 100)
    @Column(name = "granted", nullable = false)
    private Map<String, Boolean> permissions = new HashMap<>();

    /** The employees holding this custom role. */
    @ManyToMany
    @JoinTable(name = "employee_custom_role",
            joinColumns = @JoinColumn(name = "org_role_id"),
            inverseJoinColumns = @JoinColumn(name = "employee_id"))
    private Set<Employee> members = new HashSet<>();

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    public boolean isCustom() {
        return systemRole == null;
    }
}
