package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessFactsQueries;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessFactsQueries.GrantRow;

/**
 * Loads a person's access facts from the matrix tables.
 *
 * <p>Two steps, kept apart so a cache can sit between them: whether the person is an active
 * member, which is one cheap lookup and is asked fresh on every request so removal takes effect at
 * once, and what they hold, which is several queries and is worth caching.
 *
 * <p>What someone holds is the union of three sources: the organization's baseline role, the
 * roles assigned to them, and their direct grants. Grants only add, so the union is the whole
 * answer. An assignment on a project goes into that project's grants and never into the
 * organization-wide ones.
 *
 * <p>Employees with status {@code active}, {@code onLeave} or {@code probation} hold their access.
 * Any other status, a status never set, and anyone who is not an employee of the organization at
 * all hold nothing.
 */
@Component
public class DatabaseAccessFactsSource {

    static final Set<EmployeeStatus> HOLDING_ACCESS =
            EnumSet.of(EmployeeStatus.active, EmployeeStatus.onLeave, EmployeeStatus.probation);

    private final AccessFactsQueries queries;

    public DatabaseAccessFactsSource(AccessFactsQueries queries) {
        this.queries = queries;
    }

    /** Whether the person is an employee of the organization whose status carries access. */
    @Transactional(readOnly = true)
    public boolean isActiveMember(AccessSubject subject) {
        List<EmployeeStatus> status = queries.statusOf(subject.organizationId(), subject.employeeId());
        return !status.isEmpty() && status.get(0) != null && HOLDING_ACCESS.contains(status.get(0));
    }

    /** What an active member holds; call {@link #isActiveMember} first. */
    @Transactional(readOnly = true)
    public AccessFacts loadHoldings(AccessSubject subject) {
        Long org = subject.organizationId();
        Long employee = subject.employeeId();
        Map<String, Set<AccessScope>> grants = new LinkedHashMap<>();
        Map<Long, Map<String, Set<AccessScope>>> projectGrants = new HashMap<>();
        merge(grants, projectGrants, queries.baselineGrants(org));
        merge(grants, projectGrants, queries.roleGrants(org, employee));
        merge(grants, projectGrants, queries.directGrants(org, employee));
        return new AccessFacts(true, grants, projectGrants,
                new HashSet<>(queries.projectIds(org, employee)),
                new HashSet<>(queries.customerIds(org, employee)),
                new HashSet<>(queries.vendorIds(org, employee)));
    }

    private static void merge(Map<String, Set<AccessScope>> grants,
                              Map<Long, Map<String, Set<AccessScope>>> projectGrants,
                              List<GrantRow> rows) {
        for (GrantRow row : rows) {
            Map<String, Set<AccessScope>> into = row.projectId() == null
                    ? grants
                    : projectGrants.computeIfAbsent(row.projectId(), project -> new LinkedHashMap<>());
            into.computeIfAbsent(row.permissionKey(), key -> EnumSet.noneOf(AccessScope.class)).add(row.scope());
        }
    }
}
