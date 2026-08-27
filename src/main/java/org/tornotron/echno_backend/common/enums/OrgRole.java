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
 *
 * <p>Adding a constant here adds the subgroup to every organization created from
 * then on. Organizations that already exist get theirs the first time the role is
 * assigned: {@code KeycloakGroupService.assignOrgRole} creates a missing subgroup
 * rather than refusing, so no backfill over existing tenants is needed.
 */
public enum OrgRole {
    SYSTEM_ADMIN("system-admin"),
    ORG_MANAGER("org-manager"),
    HR_ADMIN("hr-admin"),
    PROJECT_MANAGER("project-manager"),
    // The three inspection roles. They are org-scoped rather than realm occupation
    // roles because this is the layer @PreAuthorize actually checks: the realm has a
    // 'site-engineer' and a 'safety-officer' occupation role already, but occupation
    // roles gate nothing in application code and are a catalogue for assignment, not
    // an authority. They are deliberately not manager roles: a QA engineer signs off
    // quality, not headcount, and getManagerRoles decides who may be named the
    // manager on a project invite code.
    QA_ENGINEER("qa-engineer"),
    SAFETY_OFFICER("safety-officer"),
    SITE_ENGINEER("site-engineer");

    private final String groupName;

    OrgRole(String groupName) {
        this.groupName = groupName;
    }

    public String getGroupName() {
        return groupName;
    }

    private static final Set<OrgRole> MANAGER_ROLES = Set.of(
            SYSTEM_ADMIN,
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
