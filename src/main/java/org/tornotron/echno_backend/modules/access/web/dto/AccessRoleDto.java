package org.tornotron.echno_backend.modules.access.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * One role as the Roles &amp; Permissions screen shows it: the role's details, its module
 * permissions and the people assigned to it.
 *
 * @param id                   the role's id
 * @param slug                 its stable machine name
 * @param name                 what the screen shows
 * @param subtitle             a short qualifier under the name, or null
 * @param description          what the role is for, or null
 * @param system               System Admin or the baseline role: fixed, apart from the baseline's
 *                             grants, which only {@code access:edit-employee-role} changes
 * @param baseline             the role every active employee holds without being assigned it
 * @param carriedOver          kept from the old roles so nobody lost access; a new organization's
 *                             screen does not offer it
 * @param organizationWideOnly the role stands in for an old role and holds across the
 *                             organization, so it cannot be given on one project yet
 * @param grants               what it grants
 * @param assignments          the people it is assigned to, each on a project or across the
 *                             organization; empty for the baseline, which everyone holds
 * @param grantableRoleIds     the roles its holders may assign to others
 */
public record AccessRoleDto(
        UUID id,
        String slug,
        String name,
        String subtitle,
        String description,
        boolean system,
        boolean baseline,
        boolean carriedOver,
        boolean organizationWideOnly,
        List<AccessGrantDto> grants,
        List<AccessAssignmentDto> assignments,
        List<UUID> grantableRoleIds) {
}
