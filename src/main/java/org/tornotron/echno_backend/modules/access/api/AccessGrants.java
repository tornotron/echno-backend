package org.tornotron.echno_backend.modules.access.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Everything a person holds in one organization: each permission with the scopes it covers.
 *
 * <p>For building navigation and authorities, where the question is what someone may do at all.
 * Decisions about a record go through {@link AccessService#check} instead, which also weighs the
 * record's facts.
 *
 * @param scopes each permission key ({@code <area>:<action>}) mapped to the scopes held for it;
 *               a permission that is absent is not held
 */
public record AccessGrants(Map<String, Set<AccessScope>> scopes) {

    private static final AccessGrants NONE = new AccessGrants(Map.of());

    public AccessGrants {
        Map<String, Set<AccessScope>> copy = new LinkedHashMap<>();
        scopes.forEach((key, held) -> {
            if (held != null && !held.isEmpty()) {
                copy.put(AccessPermission.of(key).key(), Collections.unmodifiableSet(EnumSet.copyOf(held)));
            }
        });
        scopes = Collections.unmodifiableMap(copy);
    }

    public static AccessGrants none() {
        return NONE;
    }

    /** Whether the permission is held with any scope. */
    public boolean holds(AccessPermission permission) {
        return scopes.containsKey(permission.key());
    }

    /** The scopes held for the permission; empty when it is not held. */
    public Set<AccessScope> scopesFor(AccessPermission permission) {
        return scopes.getOrDefault(permission.key(), Set.of());
    }
}
