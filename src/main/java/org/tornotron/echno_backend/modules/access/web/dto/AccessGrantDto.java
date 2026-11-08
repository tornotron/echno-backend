package org.tornotron.echno_backend.modules.access.web.dto;

/**
 * One permission with the records it covers.
 *
 * @param permission {@code <area>:<action>}, for example {@code finance:read}
 * @param scope      {@code ALL}, {@code ASSIGNED_PROJECTS}, {@code RELATED} or {@code OWN}
 */
public record AccessGrantDto(String permission, String scope) {
}
