package org.tornotron.echno_backend.modules.access.api;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The contract run against a reference engine that keeps everything in maps.
 *
 * <p>It proves the contract can be met, and it is the plainest statement of what each scope
 * means. It is not used by the application.
 */
class InMemoryAccessServiceContractTest extends AccessServiceContract {

    private final Set<AccessSubject> active = new HashSet<>();
    private final Map<AccessSubject, Map<String, Set<AccessScope>>> grants = new HashMap<>();
    private final Map<AccessSubject, Set<Long>> projects = new HashMap<>();
    private final Map<AccessSubject, Set<UUID>> customers = new HashMap<>();
    private final Map<AccessSubject, Set<Long>> vendors = new HashMap<>();

    @Override
    protected AccessService service() {
        return new InMemory();
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

    private final class InMemory implements AccessService {

        @Override
        public boolean isActiveMember(AccessSubject subject) {
            return active.contains(subject);
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission) {
            return grantsFor(subject).holds(permission);
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission, RecordFacts record) {
            return record.organizationId().equals(subject.organizationId())
                    && filter(subject, permission).admits(record);
        }

        @Override
        public AccessFilter filter(AccessSubject subject, AccessPermission permission) {
            Set<AccessScope> held = grantsFor(subject).scopesFor(permission);
            if (held.isEmpty()) {
                return AccessFilter.none();
            }
            if (held.contains(AccessScope.ALL)) {
                return AccessFilter.all();
            }
            boolean related = held.contains(AccessScope.RELATED);
            return new AccessFilter(false,
                    held.contains(AccessScope.ASSIGNED_PROJECTS) ? projects.get(subject) : null,
                    held.contains(AccessScope.OWN) ? Set.of(subject.employeeId()) : null,
                    related ? Set.of(subject.employeeId()) : null,
                    related ? customers.get(subject) : null,
                    related ? vendors.get(subject) : null);
        }

        @Override
        public AccessGrants grantsFor(AccessSubject subject) {
            if (!active.contains(subject)) {
                return AccessGrants.none();
            }
            return new AccessGrants(grants.getOrDefault(subject, Map.of()));
        }
    }
}
