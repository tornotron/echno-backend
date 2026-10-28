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
 * maps. Loading those facts from the database is tested on its own.
 */
class MatrixAccessServiceContractTest extends AccessServiceContract {

    private final Set<AccessSubject> active = new HashSet<>();
    private final Map<AccessSubject, Map<String, Set<AccessScope>>> grants = new HashMap<>();
    private final Map<AccessSubject, Set<Long>> projects = new HashMap<>();
    private final Map<AccessSubject, Set<UUID>> customers = new HashMap<>();
    private final Map<AccessSubject, Set<Long>> vendors = new HashMap<>();

    @Override
    protected AccessService service() {
        return new MatrixAccessService(subject -> active.contains(subject)
                ? new AccessFacts(true,
                        grants.getOrDefault(subject, Map.of()),
                        projects.get(subject),
                        customers.get(subject),
                        vendors.get(subject))
                : AccessFacts.noAccess());
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
        grants.computeIfAbsent(subject, s -> new LinkedHashMap<>())
                .computeIfAbsent(permission.key(), k -> EnumSet.noneOf(AccessScope.class))
                .add(scope);
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
