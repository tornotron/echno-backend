package org.tornotron.echno_backend.modules.access.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The whole set of grants a role should hold; it replaces what the role holds now. Any action
 * brings read on the same area with the same scope, so read need not be listed. A grant the role
 * already holds may be sent back as it is, even on an action the screen offers no choice for.
 *
 * @param grants the grants; empty takes every grant away
 */
public record AccessGrantsRequest(@NotNull List<@Valid @NotNull AccessGrantRequest> grants) {
}
