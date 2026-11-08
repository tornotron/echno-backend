package org.tornotron.echno_backend.role;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every permission the application knows, grouped by the module it belongs to.
 *
 * <p>This list lives in code because guards name its codes: {@code
 * @orgSecurity.hasPermission('project.create')}. Which role grants which permission is data, kept
 * per organization (see {@link RoleDefinition}); what a permission is, is not.
 *
 * <p>To add one: add a constant here, give it to the built-in roles that should have it by default
 * in {@link DefaultRolePermissions}, and use its code in a guard. Organizations pick it up with no
 * migration: a built-in role grants its defaults unless the organization has changed them.
 *
 * <p>The code is stored in {@code org_role_permission.permission}, so a code is never renamed once
 * shipped. Remove a constant and its stored rows are ignored.
 */
public enum Permission {

    PROJECT_CREATE("project.create", "project", "Create projects"),
    PROJECT_READ("project.read", "project", "Read project details, dates and status"),
    PROJECT_UPDATE("project.update", "project", "Edit a project's details, dates and status"),
    PROJECT_DELETE("project.delete", "project", "Delete projects"),
    PROJECT_MANAGE_TEAM("project.manage-team", "project", "Add people to a project or take them off it");

    private static final Map<String, Permission> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Permission::getCode, Function.identity()));

    private final String code;
    private final String module;
    private final String description;

    Permission(String code, String module, String description) {
        this.code = code;
        this.module = module;
        this.description = description;
    }

    /** The name guards and the API use, e.g. {@code project.create}. */
    public String getCode() {
        return code;
    }

    /** The module the permission belongs to, for grouping it in a role editor. */
    public String getModule() {
        return module;
    }

    public String getDescription() {
        return description;
    }

    /** The permission with this code, or empty for a code that is not (or no longer) in the list. */
    public static Optional<Permission> fromCode(String code) {
        return Optional.ofNullable(code == null ? null : BY_CODE.get(code));
    }
}
