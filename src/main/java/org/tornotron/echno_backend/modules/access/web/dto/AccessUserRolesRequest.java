package org.tornotron.echno_backend.modules.access.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The whole set of roles a person should hold; it replaces what they hold now. The baseline role
 * is never listed, since every active employee holds it.
 *
 * @param roles the roles, each on a project or across the organization; empty takes every role away
 */
public record AccessUserRolesRequest(@NotNull List<@Valid @NotNull AccessUserRoleRequest> roles) {
}
