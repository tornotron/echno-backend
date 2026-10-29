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
 * @param grants         each permission the caller holds ({@code <area>:<action>}) with the
 *                       scopes it covers: {@code ALL}, {@code ASSIGNED_PROJECTS},
 *                       {@code RELATED} or {@code OWN}
 */
public record CurrentUserAccessDto(Long organizationId, Long employeeId, Map<String, List<String>> grants) {
}
