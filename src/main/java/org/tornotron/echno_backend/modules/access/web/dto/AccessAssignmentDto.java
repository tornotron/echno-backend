package org.tornotron.echno_backend.modules.access.web.dto;

/**
 * One person holding a role, on one project or across the organization.
 *
 * @param employeeId the person's membership in the organization
 * @param projectId  the project the role holds on; null when it holds across the organization
 */
public record AccessAssignmentDto(Long employeeId, Long projectId) {
}
