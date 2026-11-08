package org.tornotron.echno_backend.modules.access.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * The whole set of roles a role's holders may assign; it replaces the current set.
 *
 * @param roleIds the roles; empty clears the set
 */
public record AccessRoleIdsRequest(@NotNull List<@NotNull UUID> roleIds) {
}
