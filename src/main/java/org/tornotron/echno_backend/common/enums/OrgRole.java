package org.tornotron.echno_backend.common.enums;

import java.util.List;
import java.util.Set;

/**
 * Defines the roles that can be assigned to users within a specific organization.
 *
 * These roles are implemented as Keycloak subgroups under each organization's group.
 * For example, if organization 5 exists with group "org-5", assigning SYSTEM_ADMIN
 * to a user puts them in the "org-5/system-admin" subgroup.
 *
 * When the JWT is parsed, the group path "/org-5/system-admin" becomes the
 * Spring Security authority "ORG_5_ROLE_system-admin", which can be checked via:
 *   @PreAuthorize("@orgSecurity.hasOrgRole(#orgId, 'system-admin')")
 *
 * The groupName field is the actual Keycloak subgroup name (kebab-case).
 */
public enum OrgRole {
    SYSTEM_ADMIN("system-admin"),
    ORG_MANAGER("org-manager"),
    HR_ADMIN("hr-admin"),
    PROJECT_MANAGER("project-manager");

    private final String groupName;

    OrgRole(String groupName) {
        this.groupName = groupName;
    }

    public String getGroupName() {
        return groupName;
    }

    private static final Set<OrgRole> MANAGER_ROLES = Set.of(
            ORG_MANAGER,
            HR_ADMIN,
            PROJECT_MANAGER
    );

    public static Set<OrgRole> getManagerRoles() {
        return MANAGER_ROLES;
    }

    public static boolean isManagerRole(OrgRole role) {
        return MANAGER_ROLES.contains(role);
    }
}
