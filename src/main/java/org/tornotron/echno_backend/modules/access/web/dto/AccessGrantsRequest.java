package org.tornotron.echno_backend.modules.access.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The whole set of grants a role, group or person should hold; it replaces what they hold now.
 * Any action brings view on the same area with the same scope, so view need not be listed.
 *
 * @param grants the grants; empty takes every grant away
 */
public record AccessGrantsRequest(@NotNull List<@Valid @NotNull AccessGrantRequest> grants) {
}
