package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.EnumSet;
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
 * <p>What someone holds is the union of five sources: the organization's baseline role, the
 * roles assigned to them, the roles of their groups, their groups' own grants, and their direct
 * grants. Grants only add, so the union is the whole answer.
 *
 * <p>A person whose membership is inactive, terminated, resigned or suspended holds nothing; so
 * does anyone who is not an employee of the organization at all. Being on leave or on probation
 * does not take access away.
 */
@Component
public class DatabaseAccessFactsSource implements AccessFactsSource {

    static final Set<EmployeeStatus> WITHOUT_ACCESS = EnumSet.of(
            EmployeeStatus.inactive, EmployeeStatus.terminated, EmployeeStatus.resigned, EmployeeStatus.suspended);

    private final AccessFactsQueries queries;

    public DatabaseAccessFactsSource(AccessFactsQueries queries) {
        this.queries = queries;
    }

    @Override
    @Transactional(readOnly = true)
    public AccessFacts load(AccessSubject subject) {
        Long org = subject.organizationId();
        Long employee = subject.employeeId();
        List<EmployeeStatus> status = queries.statusOf(org, employee);
        if (status.isEmpty() || WITHOUT_ACCESS.contains(status.get(0))) {
            return AccessFacts.noAccess();
        }
        Map<String, Set<AccessScope>> grants = new LinkedHashMap<>();
        merge(grants, queries.baselineGrants(org));
        merge(grants, queries.directRoleGrants(org, employee));
        merge(grants, queries.groupRoleGrants(org, employee));
        merge(grants, queries.groupGrants(org, employee));
        merge(grants, queries.directGrants(org, employee));
        return new AccessFacts(true, grants,
                new HashSet<>(queries.projectIds(org, employee)),
                new HashSet<>(queries.customerIds(org, employee)),
                new HashSet<>(queries.vendorIds(org, employee)));
    }

    private static void merge(Map<String, Set<AccessScope>> into, List<GrantRow> rows) {
        for (GrantRow row : rows) {
            into.computeIfAbsent(row.permissionKey(), key -> EnumSet.noneOf(AccessScope.class)).add(row.scope());
        }
    }
}
