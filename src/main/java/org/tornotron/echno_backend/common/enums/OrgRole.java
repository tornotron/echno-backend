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
    SITE_ENGINEER("site-engineer"),
    // The stores function. Before this role the Resources domain had nothing between plain
    // organization membership and system-admin, so booking a delivery meant handing the person
    // on the store counter the authority to delete projects and edit anybody's employee record.
    // What it grants is the storekeeper's own work and the reads those forms need: goods
    // receipts, material issues, site transfers out and in, and the count corrections that
    // reconcile a shelf to the ledger, plus read access to the catalogue, storage locations,
    // stock ledger, indents, purchase orders and vendor identity that filling those forms
    // requires. What it does not grant is the second pair of eyes on any of it: no approval or
    // rejection of a stock adjustment, no deletions, and no catalogue, vendor or purchase-order
    // writes. A storekeeper who both counts and approves their own correction is the shape
    // SelfApprovalPolicy exists to refuse, and a role that could do both halves would defeat it
    // one request earlier than the policy is consulted.
    //
    // Deliberately not a manager role, for the same reason the three inspection roles are not:
    // getManagerRoles decides who may be named the manager on a project invite code and who
    // appears in the manager listings, and running a store is not managing headcount.
    STORE_KEEPER("store-keeper");

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
