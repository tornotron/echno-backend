package org.tornotron.echno_backend.modules.access.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * One permission to grant, with the records it covers.
 *
 * @param permission {@code <area>:<action>} from the catalog, for example {@code wbs:edit}
 * @param scope      {@code ALL}, {@code ASSIGNED_PROJECTS}, {@code RELATED} or {@code OWN}
 */
public record AccessGrantRequest(@NotBlank String permission, @NotBlank String scope) {
}
