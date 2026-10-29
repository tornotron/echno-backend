package org.tornotron.echno_backend.modules.access.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalog;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantRequest;

/**
 * The grants a write asks for, checked against the catalog. Any action brings view on the same
 * area with the same scope, the rule the starting roles follow, so a role that may edit an area
 * can always see it.
 */
final class RequestedGrants {

    /** One permission with one scope. */
    record Grant(AccessPermission permission, AccessScope scope) {

        String key() {
            return permission.key();
        }
    }

    private RequestedGrants() {
    }

    /**
     * The grants asked for, with view added alongside every other action and repeats dropped.
     *
     * @throws InvalidRequestException when a permission is not in the catalog or a scope is unknown
     */
    static Set<Grant> parse(List<AccessGrantRequest> requested) {
        Set<Grant> grants = new LinkedHashSet<>();
        for (AccessGrantRequest request : requested) {
            AccessPermission permission = permission(request.permission());
            AccessScope scope = scope(request.scope());
            grants.add(new Grant(new AccessPermission(permission.area(), AccessPermission.VIEW), scope));
            grants.add(new Grant(permission, scope));
        }
        return grants;
    }

    private static AccessPermission permission(String key) {
        AccessPermission permission;
        try {
            permission = AccessPermission.of(key);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("'" + key + "' is not a permission; write it <area>:<action>");
        }
        if (!AccessCatalog.offers(permission)) {
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
