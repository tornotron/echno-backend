package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.tornotron.echno_backend.modules.access.api.AccessScope;

/**
 * What an active member holds, in the shape the cache stores.
 *
 * <p>Only plain, mutable JDK types and strings, so the value survives the Redis JSON serializer,
 * which records each collection's concrete class and cannot rebuild the immutable ones
 * {@link AccessFacts} holds. Whether the person is active is never cached.
 *
 * @param grants        each permission key held across the organization, mapped to its scope names
 * @param projectGrants for each project id, as a string, the permissions held only there
 * @param projectIds    the person's projects
 * @param customerIds   the customers they represent, as strings
 * @param vendorIds     the vendors they represent
 */
public record CachedHoldings(
        HashMap<String, ArrayList<String>> grants,
        HashMap<String, HashMap<String, ArrayList<String>>> projectGrants,
        ArrayList<Long> projectIds,
        ArrayList<String> customerIds,
        ArrayList<Long> vendorIds) {

    static CachedHoldings of(AccessFacts facts) {
        HashMap<String, HashMap<String, ArrayList<String>>> projectGrants = new HashMap<>();
        facts.projectGrants().forEach((project, held) -> projectGrants.put(project.toString(), names(held)));
        ArrayList<String> customers = new ArrayList<>();
        facts.customerIds().forEach(id -> customers.add(id.toString()));
        return new CachedHoldings(names(facts.grants()), projectGrants, new ArrayList<>(facts.projectIds()),
                customers, new ArrayList<>(facts.vendorIds()));
    }

    AccessFacts toFacts() {
        Map<Long, Map<String, Set<AccessScope>>> onProjects = new HashMap<>();
        projectGrants.forEach((project, held) -> onProjects.put(Long.valueOf(project), scopes(held)));
        Set<UUID> customers = new HashSet<>();
        customerIds.forEach(id -> customers.add(UUID.fromString(id)));
        return new AccessFacts(true, scopes(grants), onProjects, new HashSet<>(projectIds), customers,
                new HashSet<>(vendorIds));
    }

    private static HashMap<String, ArrayList<String>> names(Map<String, Set<AccessScope>> held) {
        HashMap<String, ArrayList<String>> names = new HashMap<>();
        held.forEach((key, scopes) -> {
            ArrayList<String> list = new ArrayList<>();
            scopes.forEach(scope -> list.add(scope.name()));
            names.put(key, list);
        });
        return names;
    }

    private static Map<String, Set<AccessScope>> scopes(Map<String, ArrayList<String>> names) {
        Map<String, Set<AccessScope>> held = new HashMap<>();
        names.forEach((key, list) -> {
            Set<AccessScope> scopes = EnumSet.noneOf(AccessScope.class);
            list.forEach(name -> scopes.add(AccessScope.valueOf(name)));
            held.put(key, scopes);
        });
        return held;
    }
}
