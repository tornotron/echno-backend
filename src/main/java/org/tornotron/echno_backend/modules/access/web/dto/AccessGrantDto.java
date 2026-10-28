package org.tornotron.echno_backend.modules.access.web.dto;

/**
 * One permission with the records it covers.
 *
 * @param permission {@code <area>:<action>}, for example {@code wbs:edit}
 * @param scope      {@code ALL}, {@code ASSIGNED_PROJECTS}, {@code RELATED} or {@code OWN}
 * @param locked     a grant that cannot be removed, such as everyone's access to their own leave
 */
public record AccessGrantDto(String permission, String scope, boolean locked) {
}
