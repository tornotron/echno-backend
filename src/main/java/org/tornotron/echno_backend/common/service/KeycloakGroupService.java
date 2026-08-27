package org.tornotron.echno_backend.common.service;

import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.GroupRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.enums.OrgRole;

import java.util.List;

/**
 * Manages Keycloak groups for organization membership and org-scoped roles.
 *
 * Group structure in Keycloak:
 *
 *   org-{id}/                  ← parent group (org membership)
 *     ├── system-admin         ← role subgroup
 *     ├── org-manager          ← role subgroup
 *     ├── hr-admin             ← role subgroup
 *     └── project-manager      ← role subgroup
 *
 * When a user is added to "org-5", their JWT gets group "/org-5" → authority ORG_MEMBER_5
 * When a user is added to "org-5/system-admin", JWT gets "/org-5/system-admin" → authority ORG_5_ROLE_system-admin
 *
 * The parent group handles MEMBERSHIP (are you part of this org?).
 * The subgroups handle ROLES (what can you do within this org?).
 */
@Slf4j
@Service
public class KeycloakGroupService {

    @Value("${keycloak-initializer.url}")
    private String authServerUrl;

    @Value("${keycloak-initializer.application-realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.secret}")
    private String clientSecret;

    // ==========================================
    // ORGANIZATION GROUP METHODS (existing)
    // ==========================================

    /**
     * Creates the top-level organization group in Keycloak and its default role subgroups.
     *
     * After this call, the Keycloak group tree will look like:
     *   org-{organizationId}/
     *     ├── system-admin
     *     ├── org-manager
     *     ├── hr-admin
     *     └── project-manager
     *
     * @return the Keycloak group ID of the parent org group
     */
    public String createOrganizationGroup(String organizationId, String organizationName) {
        Keycloak keycloak = getKeycloakAdminClient();

        try {
            // Step 1: Create the parent organization group (e.g., "org-5")
            GroupRepresentation group = new GroupRepresentation();
            group.setName("org-" + organizationId);
            group.singleAttribute("organizationId", organizationId);
            group.singleAttribute("organizationName", organizationName);

            Response response = keycloak.realm(realm).groups().add(group);

            if (response.getStatus() != 201) {
                String error = response.readEntity(String.class);
                response.close();
                throw new RuntimeException(
                        "Failed to create organization group 'org-" + organizationId + "' in Keycloak (status "
                                + response.getStatus() + "): " + error);
            }

            String locationHeader = response.getHeaderString("Location");
            String groupId = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);
            response.close();

            log.info("Created organization group 'org-{}' with Keycloak ID: {}", organizationId, groupId);

            // Step 2: Create default role subgroups under this org group
            createDefaultRoleSubgroups(keycloak, groupId, organizationId);

            return groupId;
        } finally {
            keycloak.close();
        }
    }

    public void addUserToOrganization(String userId, String organizationId) {
        Keycloak keycloak = getKeycloakAdminClient();

        try {
            String groupId = findOrgGroupId(keycloak, organizationId);
            if (groupId == null) {
                throw new RuntimeException(
                        "Cannot add user to organization: group 'org-" + organizationId + "' was not found in Keycloak");
            }
            keycloak.realm(realm).users().get(userId).joinGroup(groupId);
            log.info("Added user {} to organization group 'org-{}'", userId, organizationId);
        } finally {
            keycloak.close();
        }
    }

    public void removeUserFromOrganization(String userId, String organizationId) {
        Keycloak keycloak = getKeycloakAdminClient();

        try {
            String groupId = findOrgGroupId(keycloak, organizationId);
            if (groupId == null) {
                log.warn("Cannot remove user from organization: organization group 'org-{}' not found in Keycloak", organizationId);
                return;
            }
            keycloak.realm(realm).users().get(userId).leaveGroup(groupId);
            log.info("Removed user {} from organization group 'org-{}'", userId, organizationId);
        } finally {
            keycloak.close();
        }
    }

    /**
     * Deletes the organization group and ALL its subgroups (role subgroups are deleted automatically
     * by Keycloak when the parent group is removed).
     */
    public void deleteOrganizationGroup(String organizationId) {
        Keycloak keycloak = getKeycloakAdminClient();

        try {
            String groupId = findOrgGroupId(keycloak, organizationId);
            if (groupId != null) {
                keycloak.realm(realm).groups().group(groupId).remove();
                log.info("Deleted organization group 'org-{}' and all role subgroups", organizationId);
            }
        } finally {
            keycloak.close();
        }
    }

    public List<String> getUserOrganizations(String userId) {
        Keycloak keycloak = getKeycloakAdminClient();

        try {
            List<GroupRepresentation> userGroups = keycloak.realm(realm)
                    .users().get(userId).groups();

            return userGroups.stream()
                    .filter(g -> g.getName().startsWith("org-"))
                    .map(g -> g.getName().substring(4)) // Remove "org-" prefix
                    .toList();
        } finally {
            keycloak.close();
        }
    }

    // ==========================================
    // ORG-SCOPED ROLE METHODS (new)
    // ==========================================

    /**
     * Assigns an org-scoped role to a user.
     *
     * This adds the user to the role subgroup under the org's parent group.
     * For example, assignOrgRole("user-uuid", "5", OrgRole.SYSTEM_ADMIN)
     * adds the user to subgroup "org-5/system-admin".
     *
     * After this, the user's next JWT will include "/org-5/system-admin" in the
     * groups claim, which JwtAuthConverter converts to authority "ORG_5_ROLE_system-admin".
     *
     * NOTE: The user must ALSO be a member of the org (via addUserToOrganization).
     * Role assignment and membership are separate: a user can be a member without
     * any role, but should not have a role without being a member.
     *
     * A subgroup that does not exist yet is created here rather than refused. The
     * subgroups are created from OrgRole when an organization is created, so an
     * organization created before a role was added to the enum has no subgroup for
     * it, and every existing tenant is in that position the moment a role is added.
     * Creating it on first assignment keeps that a non-event instead of a migration
     * over every tenant, and it can only ever create a name the enum already names.
     */
    public void assignOrgRole(String userId, String organizationId, OrgRole role) {
        Keycloak keycloak = getKeycloakAdminClient();

        try {
            String orgGroupId = findOrgGroupId(keycloak, organizationId);
            if (orgGroupId == null) {
                throw new RuntimeException(
                        "Cannot assign role: group 'org-" + organizationId + "' was not found in Keycloak");
            }

            String subgroupId = ensureRoleSubgroup(keycloak, orgGroupId, organizationId, role);

            keycloak.realm(realm).users().get(userId).joinGroup(subgroupId);
            log.info("Assigned role '{}' to user {} in organization {}", role.getGroupName(), userId, organizationId);

        } finally {
            keycloak.close();
        }
    }

    /**
     * Removes an org-scoped role from a user.
     *
     * This removes the user from the role subgroup. The user remains a member of the
     * organization (their membership in the parent "org-{id}" group is not affected).
     */
    public void removeOrgRole(String userId, String organizationId, OrgRole role) {
        Keycloak keycloak = getKeycloakAdminClient();

        try {
            String orgGroupId = findOrgGroupId(keycloak, organizationId);
            if (orgGroupId == null) {
                throw new RuntimeException(
                        "Cannot remove role: group 'org-" + organizationId + "' was not found in Keycloak");
            }

            String subgroupId = findRoleSubgroupId(keycloak, orgGroupId, role.getGroupName());
            if (subgroupId == null) {
                throw new RuntimeException(
                        "Cannot remove role: subgroup '" + role.getGroupName() + "' was not found under 'org-"
                                + organizationId + "'");
            }

            keycloak.realm(realm).users().get(userId).leaveGroup(subgroupId);
            log.info("Removed role '{}' from user {} in organization {}", role.getGroupName(), userId, organizationId);

        } finally {
            keycloak.close();
        }
    }

    /**
     * Returns the list of org-scoped roles a user has in a specific organization.
     *
     * This works by fetching ALL groups the user belongs to, filtering for subgroups
     * of the given organization, and extracting the role names.
     *
     * For example, if user is in groups ["/org-5", "/org-5/system-admin", "/org-5/hr-admin", "/org-10"],
     * calling getUserOrgRoles(userId, "5") returns ["system-admin", "hr-admin"].
     */
    public List<String> getUserOrgRoles(String userId, String organizationId) {
        Keycloak keycloak = getKeycloakAdminClient();

        try {
            String orgGroupPrefix = "org-" + organizationId;

            List<GroupRepresentation> userGroups = keycloak.realm(realm)
                    .users().get(userId).groups();

            return userGroups.stream()
                    .map(GroupRepresentation::getPath)
                    // Path looks like "/org-5/system-admin" — strip leading "/"
                    .map(path -> path.startsWith("/") ? path.substring(1) : path)
                    // Keep only subgroups of this org (must have a "/" after the org prefix)
                    .filter(path -> path.startsWith(orgGroupPrefix + "/"))
                    // Extract role name: "org-5/system-admin" → "system-admin"
                    .map(path -> path.substring(orgGroupPrefix.length() + 1))
                    .toList();
        } finally {
            keycloak.close();
        }
    }

    // ==========================================
    // PRIVATE HELPERS
    // ==========================================

    /**
     * Creates default role subgroups under an organization group.
     * Called automatically when a new organization is created.
     *
     * Each OrgRole enum value becomes a subgroup. For example, under "org-5":
     *   system-admin, org-manager, hr-admin, project-manager
     */
    private void createDefaultRoleSubgroups(Keycloak keycloak, String orgGroupId, String organizationId) {
        for (OrgRole role : OrgRole.values()) {
            createRoleSubgroup(keycloak, orgGroupId, organizationId, role);
        }
    }

    /**
     * The id of a role subgroup, creating it if this organization does not have one.
     *
     * Organizations get their subgroups from OrgRole at creation, so one created
     * before a role was added to the enum is missing that subgroup. Rather than
     * refuse the assignment and leave an operator to create groups by hand in
     * Keycloak for every existing tenant, the subgroup is created on demand. The
     * name always comes from the enum, so this cannot invent an authority.
     */
    private String ensureRoleSubgroup(Keycloak keycloak, String orgGroupId,
                                      String organizationId, OrgRole role) {
        String subgroupId = findRoleSubgroupId(keycloak, orgGroupId, role.getGroupName());
        if (subgroupId != null) {
            return subgroupId;
        }
        log.info("Role subgroup '{}' is missing under org-{}; creating it",
                role.getGroupName(), organizationId);
        try {
            createRoleSubgroup(keycloak, orgGroupId, organizationId, role);
        } catch (RuntimeException e) {
            // Two assignments of the same new role can race here and the loser is told
            // the group already exists. That is the outcome it wanted, so it is only an
            // error if the subgroup still is not there when it looks again.
            if (findRoleSubgroupId(keycloak, orgGroupId, role.getGroupName()) == null) {
                throw e;
            }
            log.debug("Role subgroup '{}' under org-{} was created concurrently",
                    role.getGroupName(), organizationId);
        }

        String created = findRoleSubgroupId(keycloak, orgGroupId, role.getGroupName());
        if (created == null) {
            throw new RuntimeException(
                    "Cannot assign role: subgroup '" + role.getGroupName()
                            + "' was created under 'org-" + organizationId + "' but cannot be read back");
        }
        return created;
    }

    private void createRoleSubgroup(Keycloak keycloak, String orgGroupId,
                                    String organizationId, OrgRole role) {
        GroupRepresentation subgroup = new GroupRepresentation();
        subgroup.setName(role.getGroupName());

        Response response = keycloak.realm(realm).groups().group(orgGroupId).subGroup(subgroup);

        if (response.getStatus() != 201) {
            String error = response.readEntity(String.class);
            response.close();
            throw new RuntimeException(
                    "Failed to create role subgroup '" + role.getGroupName() + "' under 'org-" + organizationId
                            + "' in Keycloak (status " + response.getStatus() + "): " + error);
        }

        response.close();

        log.debug("Created role subgroup '{}' under org-{}", role.getGroupName(), organizationId);
    }

    /**
     * Finds the Keycloak group ID for an organization's parent group.
     * Searches for group named "org-{organizationId}".
     *
     * @return the group ID, or null if not found
     */
    private String findOrgGroupId(Keycloak keycloak, String organizationId) {
        List<GroupRepresentation> groups = keycloak.realm(realm).groups()
                .groups("org-" + organizationId, 0, 1);

        if (groups.isEmpty()) {
            log.warn("Organization group 'org-{}' not found in Keycloak", organizationId);
            return null;
        }
        return groups.getFirst().getId();
    }

    /**
     * Finds the Keycloak group ID for a role subgroup within an org group.
     *
     * This explicitly fetches the subgroups of the parent org group
     * and searches for a subgroup with the matching role name.
     *
     * @param orgGroupId the Keycloak ID of the parent org group
     * @param roleName   the subgroup name to find (e.g., "system-admin")
     * @return the subgroup ID, or null if not found
     */
    private String findRoleSubgroupId(Keycloak keycloak, String orgGroupId, String roleName) {
        // Explicitly fetch subgroups using getSubGroups() API with parameters
        // Parameters: first (offset), max (limit), briefRepresentation
        List<GroupRepresentation> subGroups = keycloak.realm(realm).groups()
                .group(orgGroupId).getSubGroups(0, Integer.MAX_VALUE, false);

        if (subGroups == null || subGroups.isEmpty()) {
            log.warn("No subgroups found for organization group ID: {}", orgGroupId);
            return null;
        }

        return subGroups.stream()
                .filter(sg -> sg.getName().equals(roleName))
                .findFirst()
                .map(GroupRepresentation::getId)
                .orElse(null);
    }

    private Keycloak getKeycloakAdminClient() {
        return KeycloakBuilder.builder()
                .serverUrl(authServerUrl)
                .realm(realm)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .grantType("client_credentials")
                .build();
    }
}
