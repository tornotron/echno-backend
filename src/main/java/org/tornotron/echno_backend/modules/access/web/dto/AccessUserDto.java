package org.tornotron.echno_backend.modules.access.web.dto;

import java.util.List;

/**
 * One person's access as the Roles &amp; Permissions screen shows it: what is given to them, not
 * the merged result, which {@code /current-user-access} answers for the caller. The baseline role
 * is not listed, since every active employee holds it.
 *
 * @param employeeId  the person's membership in the organization
 * @param name        their name
 * @param email       their email address
 * @param designation their job title, or null
 * @param active      whether their status still gives them access
 * @param roles       the roles assigned to them, each on a project or across the organization
 * @param grants      what is granted to them directly, on top of their roles
 */
public record AccessUserDto(
        Long employeeId,
        String name,
        String email,
        String designation,
        boolean active,
        List<AccessUserRoleDto> roles,
        List<AccessUserGrantDto> grants) {
}
