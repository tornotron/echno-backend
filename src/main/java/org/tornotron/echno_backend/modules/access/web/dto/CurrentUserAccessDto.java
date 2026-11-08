package org.tornotron.echno_backend.modules.access.web.dto;

import java.util.List;
import java.util.Map;

/**
 * What the caller may do in the current organization, for the apps to decide what to show.
 *
 * <p>This is for display only: every endpoint checks access again on the server, so hiding a
 * button here never stands in for a check there.
 *
 * @param organizationId the organization the answer is for
 * @param employeeId     the caller's membership in it
 * @param grants         each permission the caller holds across the organization
 *                       ({@code <area>:<action>}) with the scopes it covers: {@code ALL},
 *                       {@code ASSIGNED_PROJECTS}, {@code RELATED} or {@code OWN}
 * @param projectGrants  for each project id, the permissions held only on that project, mapped
 *                       the same way; {@code ALL} there means every record of that project
 */
public record CurrentUserAccessDto(
        Long organizationId,
        Long employeeId,
        Map<String, List<String>> grants,
        Map<Long, Map<String, List<String>>> projectGrants) {
}
