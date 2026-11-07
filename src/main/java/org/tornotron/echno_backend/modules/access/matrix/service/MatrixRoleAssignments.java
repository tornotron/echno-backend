package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessRoleAssignments;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessChangeLog;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessChangeLogRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;

/**
 * The only writer of a person's role assignments, apart from the backfill adding what the old table
 * already says.
 *
 * <p>Each change first reconciles the organization, so it never depends on the startup task having
 * run, and takes a row lock on the organization's administrator role, so two changes that could each
 * leave it with no administrator cannot both pass the count. A change to a role with an old counterpart
 * writes the old table too, through {@link LegacyRoleMirror}.
 */
@Service
public class MatrixRoleAssignments implements AccessRoleAssignments {

    static final String ADMINISTRATOR = "system-admin";

    private static final String CHANGE_ASSIGNED = "role.assigned";
    private static final String CHANGE_REMOVED = "role.removed";
    private static final String TARGET_EMPLOYEE = "employee";
    private static final String ROLE_AUTHORITY_INFIX = "_ROLE_";

    private record Role(UUID id, String slug, @Nullable String legacyOrgRole) {
    }

    /** Who is making the change; a system change has no rules to check against a caller. */
    private record Actor(boolean system, @Nullable Long employeeId, @Nullable Long userId) {
    }

    private final JdbcTemplate jdbc;
    private final AccessRoleReconciler reconciler;
    private final LegacyRoleMirror mirror;
    private final AccessChangeLogRepository changeLog;
    private final OrganizationRepository organizations;
    private final CurrentEmployeeService currentEmployee;
    private final CachedAccessFactsSource facts;

    public MatrixRoleAssignments(JdbcTemplate jdbc, AccessRoleReconciler reconciler, LegacyRoleMirror mirror,
                                 AccessChangeLogRepository changeLog, OrganizationRepository organizations,
                                 CurrentEmployeeService currentEmployee, CachedAccessFactsSource facts) {
        this.jdbc = jdbc;
        this.reconciler = reconciler;
        this.mirror = mirror;
        this.changeLog = changeLog;
        this.organizations = organizations;
        this.currentEmployee = currentEmployee;
        this.facts = facts;
    }

    @Override
    @Transactional
    public void assignOrgRole(Long employeeId, OrgRole role) {
        Long organization = prepare();
        assign(organization, employeeId, roleCounterpartOf(organization, role), null, caller());
    }

    @Override
    @Transactional
    public void assignOrgRoleAsSystem(Long employeeId, OrgRole role) {
        Long organization = prepare();
        assign(organization, employeeId, roleCounterpartOf(organization, role), null, system());
    }

    @Override
    @Transactional
    public void removeOrgRole(Long employeeId, OrgRole role) {
        Long organization = prepare();
        remove(organization, employeeId, roleCounterpartOf(organization, role), null, caller());
    }

    @Override
    @Transactional
    public void assign(Long employeeId, UUID roleId, @Nullable Long projectId) {
        Long organization = prepare();
        assign(organization, employeeId, role(organization, roleId), projectId, caller());
    }

    @Override
    @Transactional
    public void assignAsSystem(Long employeeId, UUID roleId, @Nullable Long projectId) {
        Long organization = prepare();
        assign(organization, employeeId, role(organization, roleId), projectId, system());
    }

    @Override
    @Transactional
    public void remove(Long employeeId, UUID roleId, @Nullable Long projectId) {
        Long organization = prepare();
        remove(organization, employeeId, role(organization, roleId), projectId, caller());
    }

    @Override
    @Transactional
    public void checkAssignable(UUID roleId, @Nullable Long projectId) {
        Long organization = prepare();
        checkTheGrant(organization, null, role(organization, roleId), projectId, caller());
    }

    @Override
    @Transactional
    public void refuseIfLastAdministrator(Long employeeId) {
        Long organization = prepare();
        if (isLastAdministrator(organization, employeeId)) {
            throw new InvalidRequestException("This is the organization's last active System Admin. "
                    + "Make someone else a System Admin first.");
        }
    }

    private Long prepare() {
        Long organization = TenantContext.getCurrentOrgId();
        if (organization == null) {
            throw new InvalidRequestException("A role change needs an organization");
        }
        // Locked first, so a change waiting its turn reads nothing it would have to throw away. Locked
        // again because an organization the backfill has not reached has no such row until reconciled.
        lockTheAdministratorRole(organization);
        reconciler.reconcile(organization);
        lockTheAdministratorRole(organization);
        return organization;
    }

    /** What makes the last-administrator count hold against a concurrent change. */
    private void lockTheAdministratorRole(Long organization) {
        jdbc.queryForList("SELECT id FROM access_role WHERE organization_id = ? AND slug = ? FOR UPDATE",
                UUID.class, organization, ADMINISTRATOR);
    }

    private void assign(Long organization, Long employeeId, Role role, @Nullable Long projectId, Actor actor) {
        checkTheChange(organization, employeeId, role, projectId, actor);
        int added = jdbc.update("""
                INSERT INTO access_employee_role (id, organization_id, employee_id, role_id, project_id, created_at, created_by)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, now(), ?)
                ON CONFLICT DO NOTHING
                """, organization, employeeId, role.id(), projectId, actor.userId());
        if (role.legacyOrgRole() != null) {
            mirror.add(organization, employeeId, role.legacyOrgRole());
        }
        if (added > 0) {
            record(organization, actor, CHANGE_ASSIGNED, employeeId, null, state(role, projectId));
            facts.evictAfterCommit(organization, employeeId);
        }
    }

    private void remove(Long organization, Long employeeId, Role role, @Nullable Long projectId, Actor actor) {
        checkTheChange(organization, employeeId, role, projectId, actor);
        if (role.slug().equals(ADMINISTRATOR) && isLastAdministrator(organization, employeeId)) {
            throw new InvalidRequestException("This is the organization's last active System Admin. "
                    + "Make someone else a System Admin first.");
        }
        int removed = jdbc.update("""
                DELETE FROM access_employee_role
                WHERE organization_id = ? AND employee_id = ? AND role_id = ?
                  AND project_id IS NOT DISTINCT FROM CAST(? AS BIGINT)
                """, organization, employeeId, role.id(), projectId);
        if (role.legacyOrgRole() != null) {
            mirror.remove(organization, employeeId, role.legacyOrgRole());
        }
        if (removed > 0) {
            record(organization, actor, CHANGE_REMOVED, employeeId, state(role, projectId), null);
            facts.evictAfterCommit(organization, employeeId);
        }
    }

    /** Whatever the change, these hold: rules 1, 4 and 7 of design note 2.6, and 2.7 on a project. */
    private void checkTheChange(Long organization, Long employeeId, Role role, @Nullable Long projectId, Actor actor) {
        if (!employeeIsIn(organization, employeeId)) {
            throw new ResourceNotFoundException("Employee with ID " + employeeId + " was not found in this organization");
        }
        checkTheGrant(organization, employeeId, role, projectId, actor);
    }

    /** The rules that hold for a role and project whoever is to get them, and, when known, who that is. */
    private void checkTheGrant(Long organization, @Nullable Long employeeId, Role role, @Nullable Long projectId,
                               Actor actor) {
        if (projectId != null) {
            if (role.legacyOrgRole() != null) {
                throw new InvalidRequestException("'" + role.slug() + "' holds across the organization until the "
                        + "old role checks are gone, so it cannot be given on one project");
            }
            if (!projectIsIn(organization, projectId)) {
                throw new ResourceNotFoundException("Project with ID " + projectId + " was not found in this organization");
            }
        }
        if (actor.system()) {
            return;
        }
        if (employeeId != null && employeeId.equals(actor.employeeId())) {
            throw new AccessDeniedException("Nobody changes their own roles");
        }
        if (!mayAssign(organization, actor, role)) {
            throw new AccessDeniedException("Your roles may not assign the role '" + role.slug() + "'");
        }
    }

    /**
     * Whether any role the caller holds lists this one as grantable. A role with an old counterpart is
     * read from the authorities the old checks use, which is what lets a caller with no employee record
     * here, such as a bootstrap administrator, keep doing what they can do today.
     */
    private boolean mayAssign(Long organization, Actor actor, Role role) {
        Set<String> legacyRoles = legacyRolesOfCaller(organization);
        Set<UUID> grantedBy = new HashSet<>(jdbc.queryForList("""
                SELECT role_id FROM access_role_grantable WHERE organization_id = ? AND grantable_role_id = ?
                """, UUID.class, organization, role.id()));
        if (grantedBy.isEmpty()) {
            return false;
        }
        List<UUID> held = jdbc.queryForList(
                "SELECT role_id FROM access_employee_role WHERE organization_id = ? AND employee_id = ?",
                UUID.class, organization, actor.employeeId());
        if (held.stream().anyMatch(grantedBy::contains)) {
            return true;
        }
        return !legacyRoles.isEmpty() && grantedBy.stream().anyMatch(holder -> legacyRoles.contains(legacyRoleOf(holder)));
    }

    private String legacyRoleOf(UUID role) {
        return jdbc.queryForObject("SELECT coalesce(legacy_org_role, '') FROM access_role WHERE id = ?",
                String.class, role);
    }

    private static Set<String> legacyRolesOfCaller(Long organization) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        String prefix = "ORG_" + organization + ROLE_AUTHORITY_INFIX;
        Set<String> roles = new HashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority.getAuthority().startsWith(prefix)) {
                roles.add(authority.getAuthority().substring(prefix.length()));
            }
        }
        return roles;
    }

    /** Whether the person holds the administrator role, is active, and nobody else active does. */
    private boolean isLastAdministrator(Long organization, Long employeeId) {
        Integer holders = activeAdministrators(organization, employeeId, true);
        return holders != null && holders > 0 && Integer.valueOf(0).equals(activeAdministrators(organization, employeeId, false));
    }

    private Integer activeAdministrators(Long organization, Long employeeId, boolean thisPerson) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM access_employee_role a
                JOIN access_role r ON r.id = a.role_id
                JOIN employee e ON e.id = a.employee_id
                WHERE r.organization_id = ? AND r.slug = ? AND a.project_id IS NULL
                  AND e.status IN ('active', 'onLeave', 'probation')
                  AND (e.id = ?) = ?
                """, Integer.class, organization, ADMINISTRATOR, employeeId, thisPerson);
    }

    private boolean employeeIsIn(Long organization, Long employeeId) {
        return exists("SELECT count(*) FROM employee WHERE id = ? AND organization_id = ?", employeeId, organization);
    }

    private boolean projectIsIn(Long organization, Long projectId) {
        return exists("SELECT count(*) FROM project WHERE id = ? AND organization_id = ?", projectId, organization);
    }

    private boolean exists(String sql, Object... arguments) {
        Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
        return count != null && count > 0;
    }

    private Role roleCounterpartOf(Long organization, OrgRole role) {
        return readRole("SELECT id, slug, legacy_org_role FROM access_role WHERE organization_id = ? AND legacy_org_role = ?",
                organization, role.getGroupName())
                .orElseThrow(() -> new ResourceNotFoundException("This organization has no role for " + role.getGroupName()));
    }

    private Role role(Long organization, UUID roleId) {
        return readRole("SELECT id, slug, legacy_org_role FROM access_role WHERE organization_id = ? AND id = ?",
                organization, roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role " + roleId + " was not found in this organization"));
    }

    private Optional<Role> readRole(String sql, Object... arguments) {
        return jdbc.query(sql, (row, index) -> new Role(row.getObject(1, UUID.class), row.getString(2), row.getString(3)),
                arguments).stream().findFirst();
    }

    private Actor caller() {
        Optional<Employee> employee = currentEmployee.currentEmployee();
        return new Actor(false, employee.map(Employee::getId).orElse(null),
                employee.map(found -> found.getUser().getId()).orElse(null));
    }

    private static Actor system() {
        return new Actor(true, null, null);
    }

    private void record(Long organization, Actor actor, String action, Long employeeId,
                        @Nullable Map<String, Object> before, @Nullable Map<String, Object> after) {
        changeLog.save(AccessChangeLog.of(organizations.getReferenceById(organization), actor.userId(), action,
                TARGET_EMPLOYEE, employeeId.toString(), before, after));
    }

    private static Map<String, Object> state(Role role, @Nullable Long projectId) {
        return projectId == null
                ? Map.of("role", role.slug())
                : Map.of("role", role.slug(), "project", projectId);
    }
}
