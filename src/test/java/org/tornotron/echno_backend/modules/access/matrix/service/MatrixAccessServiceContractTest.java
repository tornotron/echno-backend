package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessServiceContract;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;

/**
 * The shared contract run against the matrix engine's decisions, with the person's facts held in
 * maps. {@link DatabaseAccessServiceContractIT} runs the same contract against the tables.
 */
class MatrixAccessServiceContractTest extends AccessServiceContract {

    private static final World WORLD = new World(7L, 8L, 100L, 200L,
            UUID.fromString("00000000-0000-0000-0000-00000000c001"),
            UUID.fromString("00000000-0000-0000-0000-00000000c002"),
            900L, 901L,
            new AccessSubject(7L, 1L, 11L),
            new AccessSubject(7L, 2L, 12L));

    private final Set<AccessSubject> active = new HashSet<>();
    private final Map<AccessSubject, Map<String, Set<AccessScope>>> grants = new HashMap<>();
    private final Map<AccessSubject, Map<Long, Map<String, Set<AccessScope>>>> projectGrants = new HashMap<>();
    private final Map<AccessSubject, Set<Long>> projects = new HashMap<>();
    private final Map<AccessSubject, Set<UUID>> customers = new HashMap<>();
    private final Map<AccessSubject, Set<Long>> vendors = new HashMap<>();

    @Override
    protected World world() {
        return WORLD;
    }

    @Override
    protected AccessService service() {
        // Every area counts as scope aware here: the contract is about what each scope admits.
        return new MatrixAccessService(subject -> active.contains(subject)
                ? new AccessFacts(true,
                        grants.getOrDefault(subject, Map.of()),
                        projectGrants.getOrDefault(subject, Map.of()),
                        projects.get(subject),
                        customers.get(subject),
                        vendors.get(subject))
                : AccessFacts.noAccess(), area -> true);
    }

    @Override
    protected void member(AccessSubject subject) {
        active.add(subject);
    }

    @Override
    protected void deactivate(AccessSubject subject) {
        active.remove(subject);
    }

    @Override
    protected void grant(AccessSubject subject, AccessPermission permission, AccessScope scope) {
        add(grants.computeIfAbsent(subject, s -> new LinkedHashMap<>()), permission, scope);
    }

    @Override
    protected void grantOnProject(AccessSubject subject, AccessPermission permission, AccessScope scope, long projectId) {
        add(projectGrants.computeIfAbsent(subject, s -> new LinkedHashMap<>())
                .computeIfAbsent(projectId, p -> new LinkedHashMap<>()), permission, scope);
    }

    private static void add(Map<String, Set<AccessScope>> held, AccessPermission permission, AccessScope scope) {
        held.computeIfAbsent(permission.key(), k -> EnumSet.noneOf(AccessScope.class)).add(scope);
    }

    @Override
    protected void assignToProject(AccessSubject subject, long projectId) {
        projects.computeIfAbsent(subject, s -> new HashSet<>()).add(projectId);
    }

    @Override
    protected void linkCustomer(AccessSubject subject, UUID customerId) {
        customers.computeIfAbsent(subject, s -> new HashSet<>()).add(customerId);
    }

    @Override
    protected void linkVendor(AccessSubject subject, long vendorId) {
        vendors.computeIfAbsent(subject, s -> new HashSet<>()).add(vendorId);
    }
}
