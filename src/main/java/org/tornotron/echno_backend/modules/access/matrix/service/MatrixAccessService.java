package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.Set;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
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
 * A record check is the filter applied to one record, so the two can never disagree.
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
        return record.organizationId().equals(subject.organizationId())
                && filter(subject, permission).admits(record);
    }

    @Override
    public AccessFilter filter(AccessSubject subject, AccessPermission permission) {
        AccessFacts held = facts.load(subject);
        if (!held.active()) {
            return AccessFilter.none();
        }
        Set<AccessScope> scopes = held.grants().getOrDefault(permission.key(), Set.of());
        if (scopes.isEmpty()) {
            return AccessFilter.none();
        }
        if (scopes.contains(AccessScope.ALL)) {
            return AccessFilter.all();
        }
        boolean related = scopes.contains(AccessScope.RELATED);
        Set<Long> self = Set.of(subject.employeeId());
        return new AccessFilter(false,
                scopes.contains(AccessScope.ASSIGNED_PROJECTS) ? held.projectIds() : null,
                scopes.contains(AccessScope.OWN) ? self : null,
                related ? self : null,
                related ? held.customerIds() : null,
                related ? held.vendorIds() : null);
    }

    @Override
    public AccessGrants grantsFor(AccessSubject subject) {
        AccessFacts held = facts.load(subject);
        return held.active() ? new AccessGrants(held.grants()) : AccessGrants.none();
    }
}
