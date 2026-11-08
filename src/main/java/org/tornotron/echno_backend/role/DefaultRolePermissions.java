package org.tornotron.echno_backend.role;

import org.tornotron.echno_backend.common.enums.OrgRole;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * What each built-in role grants when an organization has not changed it, and how an
 * organization's changes are applied on top.
 *
 * <p>The defaults reproduce the role checks the guards made before permissions existed, so moving
 * a guard from a role check to a permission check changes nobody's access until an organization
 * edits a role. {@code system-admin} is the exception to editing: it always holds every
 * permission, so an organization cannot lock its own administrators out.
 */
public final class DefaultRolePermissions {

    private static final Map<OrgRole, Set<Permission>> DEFAULTS = Map.of(
            OrgRole.PROJECT_MANAGER, EnumSet.of(
                    Permission.PROJECT_CREATE,
                    Permission.PROJECT_UPDATE,
                    Permission.PROJECT_DELETE,
                    Permission.PROJECT_MANAGE_TEAM));

    private DefaultRolePermissions() {
    }

    /** Whether an organization may change what this role grants. Only system-admin is fixed. */
    public static boolean isEditable(OrgRole role) {
        return role != OrgRole.SYSTEM_ADMIN;
    }

    /** What the role grants in an organization that has not changed it. */
    public static Set<Permission> defaultsFor(OrgRole role) {
        if (role == OrgRole.SYSTEM_ADMIN) {
            return EnumSet.allOf(Permission.class);
        }
        Set<Permission> defaults = DEFAULTS.get(role);
        return defaults == null ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(defaults);
    }

    /**
     * What the role grants in an organization, given that organization's overrides.
     *
     * @param overrides permission code to granted: {@code true} adds the permission to the
     *                  defaults, {@code false} takes it away. Codes no longer in {@link Permission}
     *                  are ignored. Ignored entirely for system-admin.
     */
    public static Set<Permission> effective(OrgRole role, Map<String, Boolean> overrides) {
        Set<Permission> result = defaultsFor(role);
        if (!isEditable(role) || overrides == null) {
            return result;
        }
        overrides.forEach((code, granted) -> Permission.fromCode(code).ifPresent(permission -> {
            if (Boolean.TRUE.equals(granted)) {
                result.add(permission);
            } else {
                result.remove(permission);
            }
        }));
        return result;
    }

    /**
     * The overrides that turn this role's defaults into exactly {@code wanted}: a grant for every
     * permission it adds, a revocation for every default it drops. Empty when {@code wanted} is the
     * defaults, so resetting a role leaves no rows behind.
     */
    public static Map<String, Boolean> overridesFor(OrgRole role, Set<Permission> wanted) {
        Set<Permission> defaults = defaultsFor(role);
        Map<String, Boolean> overrides = new HashMap<>();
        Arrays.stream(Permission.values()).forEach(permission -> {
            boolean isDefault = defaults.contains(permission);
            boolean isWanted = wanted.contains(permission);
            if (isWanted && !isDefault) {
                overrides.put(permission.getCode(), true);
            } else if (!isWanted && isDefault) {
                overrides.put(permission.getCode(), false);
            }
        });
        return overrides;
    }
}
