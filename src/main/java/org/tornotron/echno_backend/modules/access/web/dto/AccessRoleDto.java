package org.tornotron.echno_backend.modules.access.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * One role as the Roles &amp; Permissions screen shows it.
 *
 * @param id               the role's id
 * @param slug             its stable machine name
 * @param name             what the screen shows
 * @param subtitle         a short qualifier under the name, or null
 * @param description      what the role is for, or null
 * @param system           a starting role every organization has; it cannot be deleted
 * @param baseline         the role every member holds without being assigned it
 * @param grants           what it grants
 * @param memberIds        the employees it is assigned to directly; members of groups that list
 *                         it hold it too, and appear on the groups
 * @param grantableRoleIds the roles its holders may assign to others
 */
public record AccessRoleDto(
        UUID id,
        String slug,
        String name,
        String subtitle,
        String description,
        boolean system,
        boolean baseline,
        List<AccessGrantDto> grants,
        List<Long> memberIds,
        List<UUID> grantableRoleIds) {
}
