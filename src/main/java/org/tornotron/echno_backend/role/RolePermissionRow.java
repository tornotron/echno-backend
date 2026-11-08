package org.tornotron.echno_backend.role;

import org.tornotron.echno_backend.common.enums.OrgRole;

/**
 * One permission entry of one role, as the per-request permission lookup reads it.
 *
 * <p>{@link #getSystemRole()} is the built-in role the entry overrides, or null when the entry
 * belongs to a custom role. {@link #getGranted()} is true for a grant and false for a built-in
 * default taken away.
 */
public interface RolePermissionRow {

    Long getOrganizationId();

    OrgRole getSystemRole();

    String getPermission();

    Boolean getGranted();
}
