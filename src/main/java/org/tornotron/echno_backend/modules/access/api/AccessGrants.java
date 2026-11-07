package org.tornotron.echno_backend.modules.access.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Everything a person holds in one organization: each permission with the scopes it covers, kept
 * apart by whether it holds across the organization or only on one project.
 *
 * <p>For building navigation and authorities, where the question is what someone may do at all.
 * Decisions about a record go through {@link AccessService#check} instead, which also weighs the
 * record's facts.
 *
 * @param scopes        each permission key ({@code <area>:<action>}) held across the organization,
 *                      mapped to its scopes; a permission that is absent is not held that way
 * @param projectScopes for each project, the permissions held only on that project, mapped the
 *                      same way; {@link AccessScope#ALL} here means every record of that project
 */
public record AccessGrants(Map<String, Set<AccessScope>> scopes, Map<Long, Map<String, Set<AccessScope>>> projectScopes) {

    private static final AccessGrants NONE = new AccessGrants(Map.of(), Map.of());

    public AccessGrants {
        scopes = copy(scopes);
        Map<Long, Map<String, Set<AccessScope>>> onProjects = new LinkedHashMap<>();
        if (projectScopes != null) {
            projectScopes.forEach((project, held) -> {
                Map<String, Set<AccessScope>> copied = copy(held);
                if (!copied.isEmpty()) {
                    onProjects.put(project, copied);
                }
            });
        }
        projectScopes = Collections.unmodifiableMap(onProjects);
    }

    public static AccessGrants none() {
        return NONE;
    }

    /** Whether the permission is held with any scope, across the organization or on any project. */
    public boolean holds(AccessPermission permission) {
        return scopes.containsKey(permission.key())
                || projectScopes.values().stream().anyMatch(held -> held.containsKey(permission.key()));
    }

    /** The scopes held for the permission across the organization; empty when there are none. */
    public Set<AccessScope> scopesFor(AccessPermission permission) {
        return scopes.getOrDefault(permission.key(), Set.of());
    }

    /** The scopes held for the permission only on this project; empty when there are none. */
    public Set<AccessScope> scopesOn(Long projectId, AccessPermission permission) {
        return projectScopes.getOrDefault(projectId, Map.of()).getOrDefault(permission.key(), Set.of());
    }

    private static Map<String, Set<AccessScope>> copy(Map<String, Set<AccessScope>> held) {
        Map<String, Set<AccessScope>> copied = new LinkedHashMap<>();
        if (held != null) {
            held.forEach((key, values) -> {
                if (values != null && !values.isEmpty()) {
                    copied.put(AccessPermission.of(key).key(), Collections.unmodifiableSet(EnumSet.copyOf(values)));
                }
            });
        }
        return Collections.unmodifiableMap(copied);
    }
}
