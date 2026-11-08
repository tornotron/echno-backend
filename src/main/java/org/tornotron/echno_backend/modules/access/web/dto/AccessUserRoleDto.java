package org.tornotron.echno_backend.modules.access.web.dto;

import java.util.UUID;

/**
 * One role a person holds, on one project or across the organization.
 *
 * @param roleId    the role
 * @param projectId the project it holds on; null when it holds across the organization
 */
public record AccessUserRoleDto(UUID roleId, Long projectId) {
}
