package org.tornotron.echno_backend.modules.access.web.dto;

/**
 * One permission granted to a person directly, on top of their roles.
 *
 * @param permission {@code <area>:<action>}
 * @param scope      {@code ALL}, {@code ASSIGNED_PROJECTS}, {@code RELATED} or {@code OWN}
 * @param projectId  the project it holds on; null when it holds across the organization
 */
public record AccessUserGrantDto(String permission, String scope, Long projectId) {
}
