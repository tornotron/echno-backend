package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.modules.access.matrix.catalog.ScopeAwareAreas;

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
 * <p>A grant held only on a project counts only in an area that registers as scope aware. Any other
 * area answers on the whole organization and narrows none of its queries, so a project-limited grant
 * there would reach every record of the organization; it is ignored instead, and only a grant held
 * across the organization counts. An area is treated this way until it registers, so a new area fails
 * closed.
 *
 * <p>The application's {@link AccessService}; facts come from {@link CachedAccessFactsSource}.
 */
@Service
public class MatrixAccessService implements AccessService {

    private final AccessFactsSource facts;
    private final Predicate<String> scopeAware;

    /**
     * The registered areas are looked up when first asked, since the areas' own access classes depend
     * on this service.
     */
    @Autowired
    public MatrixAccessService(AccessFactsSource facts, ObjectProvider<ScopeAwareAreas> scopeAwareAreas) {
        this(facts, area -> scopeAwareAreas.getObject().isScopeAware(area));
    }

    /** With the areas whose project-limited grants count named directly. */
    public MatrixAccessService(AccessFactsSource facts, Predicate<String> scopeAware) {
        this.facts = facts;
        this.scopeAware = scopeAware;
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
        AccessFacts held = applicable(facts.load(subject));
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

    /** The facts with every project-limited grant on an area that is not scope aware left out. */
    private AccessFacts applicable(AccessFacts held) {
        if (!held.active() || held.projectGrants().isEmpty()) {
            return held;
        }
        Map<Long, Map<String, Set<AccessScope>>> projectGrants = new HashMap<>();
        held.projectGrants().forEach((project, onProject) -> {
            Map<String, Set<AccessScope>> kept = new HashMap<>();
            onProject.forEach((key, scopes) -> {
                if (scopeAware.test(areaOf(key))) {
                    kept.put(key, scopes);
                }
            });
            if (!kept.isEmpty()) {
                projectGrants.put(project, kept);
            }
        });
        return new AccessFacts(true, held.grants(), projectGrants, held.projectIds(), held.customerIds(),
                held.vendorIds());
    }

    private static String areaOf(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(0, colon);
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
        AccessFacts held = applicable(facts.load(subject));
        return held.active() ? new AccessGrants(held.grants(), held.projectGrants()) : AccessGrants.none();
    }
}
