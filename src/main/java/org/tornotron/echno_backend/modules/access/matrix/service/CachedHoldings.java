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
 * @param grants      each permission key mapped to the names of its scopes
 * @param projectIds  the person's projects
 * @param customerIds the customers they represent, as strings
 * @param vendorIds   the vendors they represent
 */
public record CachedHoldings(
        HashMap<String, ArrayList<String>> grants,
        ArrayList<Long> projectIds,
        ArrayList<String> customerIds,
        ArrayList<Long> vendorIds) {

    static CachedHoldings of(AccessFacts facts) {
        HashMap<String, ArrayList<String>> grants = new HashMap<>();
        facts.grants().forEach((key, scopes) -> {
            ArrayList<String> names = new ArrayList<>();
            scopes.forEach(scope -> names.add(scope.name()));
            grants.put(key, names);
        });
        ArrayList<String> customers = new ArrayList<>();
        facts.customerIds().forEach(id -> customers.add(id.toString()));
        return new CachedHoldings(grants, new ArrayList<>(facts.projectIds()), customers,
                new ArrayList<>(facts.vendorIds()));
    }

    AccessFacts toFacts() {
        Map<String, Set<AccessScope>> scopes = new HashMap<>();
        grants.forEach((key, names) -> {
            Set<AccessScope> held = EnumSet.noneOf(AccessScope.class);
            names.forEach(name -> held.add(AccessScope.valueOf(name)));
            scopes.put(key, held);
        });
        Set<UUID> customers = new HashSet<>();
        customerIds.forEach(id -> customers.add(UUID.fromString(id)));
        return new AccessFacts(true, scopes, new HashSet<>(projectIds), customers, new HashSet<>(vendorIds));
    }
}
