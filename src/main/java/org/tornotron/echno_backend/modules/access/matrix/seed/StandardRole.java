package org.tornotron.echno_backend.modules.access.matrix.seed;

import java.util.List;
import java.util.Objects;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;

/**
 * One role every organization starts with: its identity, what it grants, and which roles its
 * holders may hand out.
 *
 * @param slug          stable machine name; for a role that replaces a fixed org role, that
 *                      role's name, so existing assignments can be carried over
 * @param name          what the Roles &amp; Permissions screen shows
 * @param description   one line on what the role is for
 * @param legacyRole    the fixed org role this one replaces, or null
 * @param baseline      whether every member holds it without being assigned it
 * @param grants        what it grants
 * @param grantable     slugs of the roles its holders may assign to others
 */
public record StandardRole(
        String slug,
        String name,
        String description,
        String legacyRole,
        boolean baseline,
        List<Grant> grants,
        List<String> grantable) {

    /**
     * One permission with one scope. A locked grant is one the role can never drop: the baseline
     * role's access to a person's own records.
     */
    public record Grant(AccessPermission permission, AccessScope scope, boolean locked) {

        public Grant {
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(scope, "scope");
        }
    }

    public StandardRole {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(name, "name");
        grants = List.copyOf(grants);
        grantable = grantable == null ? List.of() : List.copyOf(grantable);
    }
}
