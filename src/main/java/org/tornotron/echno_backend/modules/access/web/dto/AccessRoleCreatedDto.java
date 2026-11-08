package org.tornotron.echno_backend.modules.access.web.dto;

import java.util.UUID;

/**
 * A role just created, with no grants yet.
 *
 * @param id   the new role's id
 * @param slug its stable machine name, made from its name
 */
public record AccessRoleCreatedDto(UUID id, String slug) {
}
