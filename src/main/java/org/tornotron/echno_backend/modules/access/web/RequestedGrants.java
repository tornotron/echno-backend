package org.tornotron.echno_backend.modules.access.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantRequest;

/**
 * The grants a write asks for, checked against the catalog. Any action brings read on the same
 * area with the same scope, the rule the starting roles follow, so a role that may edit an area
 * can always see it.
 */
final class RequestedGrants {

    /** One permission with one scope. */
    record Grant(AccessPermission permission, AccessScope scope) {

        String key() {
            return permission.key();
        }

        static Grant of(String permissionKey, AccessScope scope) {
            return new Grant(AccessPermission.of(permissionKey), scope);
        }
    }

    private RequestedGrants() {
    }

    /**
     * The grants asked for, with read added alongside every other action and repeats dropped.
     *
     * @throws InvalidRequestException when a permission is not in the catalog or a scope is unknown
     */
    static Set<Grant> parse(List<AccessGrantRequest> requested, Predicate<AccessPermission> declared) {
        return withRead(asked(requested, declared));
    }

    /** The grants exactly as asked for, without the reads they bring, repeats dropped. */
    static Set<Grant> asked(List<AccessGrantRequest> requested, Predicate<AccessPermission> declared) {
        Set<Grant> grants = new LinkedHashSet<>();
        for (AccessGrantRequest request : requested) {
            grants.add(new Grant(permission(request.permission(), declared), scope(request.scope())));
        }
        return grants;
    }

    /** The grants with read on the same area and scope added before each one. */
    static Set<Grant> withRead(Set<Grant> asked) {
        Set<Grant> grants = new LinkedHashSet<>();
        for (Grant grant : asked) {
            grants.add(new Grant(new AccessPermission(grant.permission().area(), AccessPermission.READ), grant.scope()));
            grants.add(grant);
        }
        return grants;
    }

    private static AccessPermission permission(String key, Predicate<AccessPermission> declared) {
        AccessPermission permission;
        try {
            permission = AccessPermission.of(key);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("'" + key + "' is not a permission; write it <area>:<action>");
        }
        if (!declared.test(permission)) {
            throw new InvalidRequestException("'" + key + "' is not in the access catalog");
        }
        return permission;
    }

    private static AccessScope scope(String name) {
        try {
            return AccessScope.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("'" + name + "' is not a scope; use ALL, ASSIGNED_PROJECTS, RELATED or OWN");
        }
    }
}
