package org.tornotron.echno_backend.modules.access.api;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;

/**
 * The contract run against a reference engine that keeps everything in maps.
 *
 * <p>It proves the contract can be met, and it is the plainest statement of what each scope
 * means. It is not used by the application.
 */
class InMemoryAccessServiceContractTest extends AccessServiceContract {

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
            return filter(subject, permission).admits(record);
        }

        @Override
        public AccessFilter filter(AccessSubject subject, AccessPermission permission) {
            AccessGrants held = grantsFor(subject);
            Set<AccessScope> scopes = held.scopesFor(permission);
            if (scopes.contains(AccessScope.ALL)) {
                return AccessFilter.all(subject.organizationId());
            }
            Set<Long> onProjects = new HashSet<>();
            if (scopes.contains(AccessScope.ASSIGNED_PROJECTS)) {
                onProjects.addAll(projects.getOrDefault(subject, Set.of()));
            }
            Map<Long, Links> withinProject = new HashMap<>();
            for (Long project : held.projectScopes().keySet()) {
                Set<AccessScope> here = held.scopesOn(project, permission);
                if (here.contains(AccessScope.ALL) || (here.contains(AccessScope.ASSIGNED_PROJECTS)
                        && projects.getOrDefault(subject, Set.of()).contains(project))) {
                    onProjects.add(project);
                }
                withinProject.put(project, links(subject, here));
            }
            return new AccessFilter(subject.organizationId(), false, onProjects, links(subject, scopes), withinProject);
        }

        private Links links(AccessSubject subject, Set<AccessScope> scopes) {
            boolean related = scopes.contains(AccessScope.RELATED);
            return new Links(
                    scopes.contains(AccessScope.OWN) ? Set.of(subject.employeeId()) : null,
                    related ? Set.of(subject.employeeId()) : null,
                    related ? customers.get(subject) : null,
                    related ? vendors.get(subject) : null);
        }

        @Override
        public AccessGrants grantsFor(AccessSubject subject) {
            if (!active.contains(subject)) {
                return AccessGrants.none();
            }
            return new AccessGrants(grants.getOrDefault(subject, Map.of()), projectGrants.getOrDefault(subject, Map.of()));
        }
    }
}
