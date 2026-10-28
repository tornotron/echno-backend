package org.tornotron.echno_backend.modules.access.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * One person's access as the Roles &amp; Permissions screen shows it: what is given to them, not
 * the merged result, which {@code /me} answers for the caller.
 *
 * @param employeeId  the person's membership in the organization
 * @param name        their name
 * @param email       their email address
 * @param designation their job title, or null
 * @param active      whether their status still gives them access
 * @param roleIds     the roles assigned to them directly
 * @param groupIds    the departments and groups they belong to
 * @param grants      what is granted to them directly, on top of their roles and groups
 */
public record AccessUserDto(
        Long employeeId,
        String name,
        String email,
        String designation,
        boolean active,
        List<UUID> roleIds,
        List<UUID> groupIds,
        List<AccessGrantDto> grants) {
}
