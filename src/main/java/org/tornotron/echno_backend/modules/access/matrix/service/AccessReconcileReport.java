package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;

/**
 * Compares, for each member of one organization whose status carries access, what the matrix gives
 * them across the organization with what they hold today.
 *
 * <p>Today's access is what the templates define for the roles the person holds: the baseline, the
 * template of each old role in {@code employee_org_roles} (each area's equivalence test proves a
 * template gives exactly what that role's old guards gave), and the template of each starting role
 * with no old counterpart assigned to them across the organization. Grants an organization gave
 * itself, through a role it made or directly to the person, count as they stand. The matrix side is
 * {@link DatabaseAccessFactsSource#loadHoldings}, the engine's own read. Grants held only on a project
 * are left out of both sides, since no old role was limited to a project.
 *
 * <p>A difference means a role is missing, an old role has no assignment, or a customized role no
 * longer matches its template. Read inside the transaction that reconciled the organization.
 */
@Component
public class AccessReconcileReport {

    /** One member whose matrix access is not today's; each entry is {@code permission/SCOPE}. */
    public record MemberDifference(Long employeeId, Set<String> missing, Set<String> extra) {
    }

    private final JdbcTemplate jdbc;
    private final DatabaseAccessFactsSource source;

    public AccessReconcileReport(JdbcTemplate jdbc, DatabaseAccessFactsSource source) {
        this.jdbc = jdbc;
        this.source = source;
    }

    /** The organization's members whose access differs, in employee id order; empty when none does. */
    public List<MemberDifference> differences(Long organizationId) {
        Map<Long, Long> users = new TreeMap<>();
        jdbc.query("SELECT id, user_id, status FROM employee WHERE organization_id = ?", row -> {
            String status = row.getString("status");
            if (status != null && DatabaseAccessFactsSource.carriesAccess(statusOf(status))) {
                long user = row.getLong("user_id");
                users.put(row.getLong("id"), row.wasNull() ? null : user);
            }
        }, organizationId);
        if (users.isEmpty()) {
            return List.of();
        }

        Map<String, Set<AccessScope>> baseline = new HashMap<>();
        jdbc.query("""
                SELECT g.permission_key, g.scope FROM access_role_template t
                JOIN access_role_template_grant g ON g.template_id = t.id
                WHERE t.is_baseline
                """, row -> {
            add(baseline, row.getString("permission_key"), row.getString("scope"));
        });
        Map<Long, Map<String, Set<AccessScope>>> expected = new HashMap<>();
        // The template of each old role the person holds.
        jdbc.query("""
                SELECT e.id AS employee_id, g.permission_key, g.scope
                FROM employee e
                JOIN employee_org_roles old ON old.employee_id = e.id
                JOIN access_role_template t ON upper(replace(t.legacy_org_role, '-', '_')) = old.role
                JOIN access_role_template_grant g ON g.template_id = t.id
                WHERE e.organization_id = ?
                """, row -> {
            add(expected.computeIfAbsent(row.getLong("employee_id"), id -> new HashMap<>()),
                    row.getString("permission_key"), row.getString("scope"));
        }, organizationId);
        // The template of each starting role with no old counterpart, assigned across the organization.
        jdbc.query("""
                SELECT a.employee_id, g.permission_key, g.scope
                FROM access_employee_role a
                JOIN access_role r ON r.id = a.role_id
                JOIN access_role_template t ON t.slug = r.slug
                JOIN access_role_template_grant g ON g.template_id = t.id
                WHERE a.organization_id = ? AND a.project_id IS NULL
                  AND r.created_by IS NULL AND r.legacy_org_role IS NULL AND NOT r.is_baseline
                """, row -> {
            add(expected.computeIfAbsent(row.getLong("employee_id"), id -> new HashMap<>()),
                    row.getString("permission_key"), row.getString("scope"));
        }, organizationId);
        // What the organization gave itself: roles it made, and grants given to the person directly.
        jdbc.query("""
                SELECT a.employee_id, g.permission_key, g.scope
                FROM access_employee_role a
                JOIN access_role r ON r.id = a.role_id
                JOIN access_role_grant g ON g.role_id = r.id
                WHERE a.organization_id = ? AND a.project_id IS NULL AND r.created_by IS NOT NULL
                UNION ALL
                SELECT d.employee_id, d.permission_key, d.scope
                FROM access_employee_grant d
                WHERE d.organization_id = ? AND d.project_id IS NULL
                """, row -> {
            add(expected.computeIfAbsent(row.getLong("employee_id"), id -> new HashMap<>()),
                    row.getString("permission_key"), row.getString("scope"));
        }, organizationId, organizationId);

        List<MemberDifference> differences = new ArrayList<>();
        users.forEach((employee, user) -> {
            Map<String, Set<AccessScope>> today = new HashMap<>();
            baseline.forEach((key, scopes) -> today.computeIfAbsent(key, k -> EnumSet.noneOf(AccessScope.class))
                    .addAll(scopes));
            expected.getOrDefault(employee, Map.of()).forEach((key, scopes) ->
                    today.computeIfAbsent(key, k -> EnumSet.noneOf(AccessScope.class)).addAll(scopes));
            Map<String, Set<AccessScope>> held = source
                    .loadHoldings(new AccessSubject(organizationId, employee, user)).grants();
            Set<String> missing = uncovered(today, held);
            Set<String> extra = uncovered(held, today);
            if (!missing.isEmpty() || !extra.isEmpty()) {
                differences.add(new MemberDifference(employee, missing, extra));
            }
        });
        return differences;
    }

    /** Each grant of {@code wanted} that {@code by} does not cover; a grant on ALL covers every scope. */
    private static Set<String> uncovered(Map<String, Set<AccessScope>> wanted, Map<String, Set<AccessScope>> by) {
        Set<String> uncovered = new TreeSet<>();
        wanted.forEach((key, scopes) -> {
            Set<AccessScope> have = by.getOrDefault(key, Set.of());
            for (AccessScope scope : scopes) {
                if (!have.contains(scope) && !have.contains(AccessScope.ALL)) {
                    uncovered.add(key + "/" + scope.name());
                }
            }
        });
        return uncovered;
    }

    private static void add(Map<String, Set<AccessScope>> into, String key, String scope) {
        into.computeIfAbsent(key, k -> EnumSet.noneOf(AccessScope.class)).add(AccessScope.valueOf(scope));
    }

    private static EmployeeStatus statusOf(String status) {
        try {
            return EmployeeStatus.valueOf(status);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
