package org.tornotron.echno_backend.auth.authority;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.auth.AuthenticatedIdentity;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.service.OrgRoleAuthority;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.OrgMembership;
import org.tornotron.echno_backend.employee.OrgPermissionGrant;
import org.tornotron.echno_backend.role.DefaultRolePermissions;
import org.tornotron.echno_backend.role.Permission;
import org.tornotron.echno_backend.role.RoleDefinitionRepository;
import org.tornotron.echno_backend.role.RolePermissionRow;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code ORG_{orgId}_PERM_{code}} for every permission the caller holds, in every organization
 * they belong to.
 *
 * <p>Three sources feed it:
 * <ul>
 *   <li><b>Built-in roles</b> held on the employee row. Each grants its code defaults
 *       ({@link DefaultRolePermissions}), adjusted by the organization's overrides if it has any.
 *       {@code system-admin} grants every permission.</li>
 *   <li><b>Custom roles</b> the organization made and assigned. Each grants exactly the
 *       permissions stored on it.</li>
 *   <li><b>Direct grants</b> on the employee, given without a role. They only add.</li>
 * </ul>
 *
 * <p>Four small queries, each returning plain values, so nothing tenant-scoped is loaded before
 * the tenant is chosen. The overrides query is skipped for a caller who holds no built-in role.
 */
@Component
@RequiredArgsConstructor
public class PermissionAuthorityProvider implements AuthorityProvider {

    private final EmployeeRepository employeeRepository;
    private final RoleDefinitionRepository roleDefinitionRepository;

    @Override
    public Collection<? extends GrantedAuthority> authoritiesFor(AuthenticatedIdentity identity) {
        String subject = identity.subject();
        Set<GrantedAuthority> authorities = new HashSet<>();

        Map<Long, Set<OrgRole>> builtInRolesByOrganization = builtInRolesHeld(subject);
        if (!builtInRolesByOrganization.isEmpty()) {
            Map<Long, Map<OrgRole, Map<String, Boolean>>> overrides = builtInOverrides(subject);
            builtInRolesByOrganization.forEach((organizationId, roles) -> roles.forEach(role -> {
                Map<String, Boolean> roleOverrides = overrides
                        .getOrDefault(organizationId, Map.of())
                        .get(role);
                DefaultRolePermissions.effective(role, roleOverrides)
                        .forEach(permission -> authorities.add(grant(organizationId, permission)));
            }));
        }

        for (RolePermissionRow row : roleDefinitionRepository.findCustomGrantsForSubject(subject)) {
            Permission.fromCode(row.getPermission())
                    .ifPresent(permission -> authorities.add(grant(row.getOrganizationId(), permission)));
        }

        for (OrgPermissionGrant row : employeeRepository.findDirectPermissionsByAuthSubject(subject)) {
            Permission.fromCode(row.getPermission())
                    .ifPresent(permission -> authorities.add(grant(row.getOrganizationId(), permission)));
        }
        return authorities;
    }

    private Map<Long, Set<OrgRole>> builtInRolesHeld(String subject) {
        Map<Long, Set<OrgRole>> held = new HashMap<>();
        for (OrgMembership membership : employeeRepository.findMembershipsByAuthSubject(subject)) {
            if (membership.getRole() != null) {
                held.computeIfAbsent(membership.getOrganizationId(), id -> EnumSet.noneOf(OrgRole.class))
                        .add(membership.getRole());
            }
        }
        return held;
    }

    private Map<Long, Map<OrgRole, Map<String, Boolean>>> builtInOverrides(String subject) {
        Map<Long, Map<OrgRole, Map<String, Boolean>>> overrides = new HashMap<>();
        for (RolePermissionRow row : roleDefinitionRepository.findBuiltInOverridesForSubject(subject)) {
            overrides.computeIfAbsent(row.getOrganizationId(), id -> new EnumMap<>(OrgRole.class))
                    .computeIfAbsent(row.getSystemRole(), role -> new HashMap<>())
                    .put(row.getPermission(), row.getGranted());
        }
        return overrides;
    }

    private static GrantedAuthority grant(Long organizationId, Permission permission) {
        return new SimpleGrantedAuthority(OrgRoleAuthority.permission(organizationId, permission.getCode()));
    }
}
