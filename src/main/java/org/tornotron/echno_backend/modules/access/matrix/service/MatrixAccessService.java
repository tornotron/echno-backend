package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;

/**
 * The roles and permissions matrix as an access engine.
 *
 * <p>Every answer comes from the person's {@link AccessFacts}, which hold their merged grants,
 * projects and customer or vendor links. Each scope maps onto one condition of the filter:
 * <ul>
 *   <li>all: no condition at all;
 *   <li>assigned projects: the record's project is one of the person's;
 *   <li>related: the person is one of the record's related people, or the record carries a
 *       customer or vendor they represent;
 *   <li>own: the person is one of the record's owners.
 * </ul>
 * A grant held on one project is the same conditions narrowed to that project: all then means every
 * record of the project, and own the person's own records in it.
 *
 * <p>A record check is the filter applied to one record, so the two can never disagree.
 *
 * <p>The application's {@link AccessService}; facts come from {@link CachedAccessFactsSource}.
 */
@Service
public class MatrixAccessService implements AccessService {

    private final AccessFactsSource facts;

    public MatrixAccessService(AccessFactsSource facts) {
        this.facts = facts;
    }

    @Override
    public boolean isActiveMember(AccessSubject subject) {
        return facts.load(subject).active();
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
        Long organization = subject.organizationId();
        AccessFacts held = facts.load(subject);
        if (!held.active()) {
            return AccessFilter.none(organization);
        }
        Set<AccessScope> scopes = held.grants().getOrDefault(permission.key(), Set.of());
        if (scopes.contains(AccessScope.ALL)) {
            return AccessFilter.all(organization);
        }
        Set<Long> wholeProjects = new HashSet<>();
        if (scopes.contains(AccessScope.ASSIGNED_PROJECTS)) {
            wholeProjects.addAll(held.projectIds());
        }
        Map<Long, Links> withinProject = new HashMap<>();
        held.projectGrants().forEach((project, onProject) -> {
            Set<AccessScope> here = onProject.getOrDefault(permission.key(), Set.of());
            if (here.contains(AccessScope.ALL)
                    || (here.contains(AccessScope.ASSIGNED_PROJECTS) && held.projectIds().contains(project))) {
                wholeProjects.add(project);
            }
            withinProject.put(project, links(subject, held, here));
        });
        return new AccessFilter(organization, false, wholeProjects, links(subject, held, scopes), withinProject);
    }

    private static Links links(AccessSubject subject, AccessFacts held, Set<AccessScope> scopes) {
        boolean related = scopes.contains(AccessScope.RELATED);
        Set<Long> self = Set.of(subject.employeeId());
        return new Links(
                scopes.contains(AccessScope.OWN) ? self : null,
                related ? self : null,
                related ? held.customerIds() : null,
                related ? held.vendorIds() : null);
    }

    @Override
    public AccessGrants grantsFor(AccessSubject subject) {
        AccessFacts held = facts.load(subject);
        return held.active() ? new AccessGrants(held.grants(), held.projectGrants()) : AccessGrants.none();
    }
}
