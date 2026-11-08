package org.tornotron.echno_backend.modules.access.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * One role a person should hold.
 *
 * @param roleId    the role
 * @param projectId the project it holds on, or null for across the organization; a role that
 *                  stands in for an old role takes no project yet
 */
public record AccessUserRoleRequest(@NotNull UUID roleId, Long projectId) {
}
