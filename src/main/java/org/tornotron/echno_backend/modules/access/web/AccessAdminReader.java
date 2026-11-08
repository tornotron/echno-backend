package org.tornotron.echno_backend.modules.access.web;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeGrant;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrant;
import org.tornotron.echno_backend.modules.access.matrix.service.DatabaseAccessFactsSource;
import org.tornotron.echno_backend.modules.access.web.dto.AccessAssignmentDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserGrantDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserRoleDto;

/**
 * What the Roles &amp; Permissions screen reads, assembled in a few queries per answer rather than
 * a query per row.
 *
 * <p>Every query names the organization itself instead of relying on the org filter, and a query
 * through a role names the role's organization as well as the row's, so nothing of another
 * organization is ever read, even through a row that linked one past the table's keys.
 */
@Service
public class AccessAdminReader {

    @PersistenceContext
    private EntityManager em;

    /** The organization's roles, the baseline first and the rest by name. */
    @Transactional(readOnly = true)
    public List<AccessRoleDto> roles(Long organizationId) {
        return roles(organizationId, null);
    }

    /** One role of the organization, as {@link #roles} lists it. */
    @Transactional(readOnly = true)
    public AccessRoleDto role(Long organizationId, UUID roleId) {
        return roles(organizationId, roleId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Role " + roleId + " was not found in this organization"));
    }

    /**
     * Every employee of the organization, by name, with what is given to them directly. The whole
     * organization at once, as the screen's Users tab lists it; paging comes when an organization's
     * size needs it.
     */
    @Transactional(readOnly = true)
    public List<AccessUserDto> users(Long organizationId) {
        return users(organizationId, null);
    }

    /** One employee of the organization, as {@link #users} lists them, read on their own. */
    @Transactional(readOnly = true)
    public AccessUserDto user(Long organizationId, Long employeeId) {
        return users(organizationId, employeeId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + employeeId
                        + " was not found in this organization"));
    }

    private List<AccessRoleDto> roles(Long organizationId, @Nullable UUID roleId) {
        List<AccessRole> roles = withId(em.createQuery("SELECT DISTINCT r FROM AccessRole r LEFT JOIN FETCH r.grants "
                        + "WHERE r.organization.id = :organizationId" + only("r.id", roleId), AccessRole.class), roleId)
                .setParameter("organizationId", organizationId)
                .getResultList();
        if (roles.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<AccessAssignmentDto>> assignments = new HashMap<>();
        withId(em.createQuery("SELECT a.role.id, a.employeeId, a.projectId FROM AccessEmployeeRole a "
                        + "WHERE a.organization.id = :organizationId AND a.role.organization.id = :organizationId"
                        + only("a.role.id", roleId)
                        + " ORDER BY a.employeeId, a.projectId", Object[].class), roleId)
                .setParameter("organizationId", organizationId)
                .getResultList()
                .forEach(row -> assignments.computeIfAbsent((UUID) row[0], id -> new ArrayList<>())
                        .add(new AccessAssignmentDto((Long) row[1], (Long) row[2])));
        Map<UUID, List<UUID>> grantable = new HashMap<>();
        withId(em.createQuery("SELECT g.role.id, g.grantableRole.id FROM AccessRoleGrantable g "
                        + "WHERE g.organization.id = :organizationId AND g.role.organization.id = :organizationId "
                        + "AND g.grantableRole.organization.id = :organizationId"
                        + only("g.role.id", roleId)
                        + " ORDER BY g.grantableRole.name", Object[].class), roleId)
                .setParameter("organizationId", organizationId)
                .getResultList()
                .forEach(row -> grantable.computeIfAbsent((UUID) row[0], id -> new ArrayList<>()).add((UUID) row[1]));

        return roles.stream()
                .sorted(Comparator.comparing(AccessRole::isBaseline).reversed()
                        .thenComparing(AccessRole::getName, String.CASE_INSENSITIVE_ORDER))
                .map(role -> new AccessRoleDto(role.getId(), role.getSlug(), role.getName(), role.getSubtitle(),
                        role.getDescription(), role.isSystem(), role.isBaseline(), role.isCarriedOver(),
                        role.getLegacyOrgRole() != null, grants(role.getGrants()),
                        assignments.getOrDefault(role.getId(), List.of()),
                        grantable.getOrDefault(role.getId(), List.of())))
                .toList();
    }

    private List<AccessUserDto> users(Long organizationId, @Nullable Long employeeId) {
        List<Object[]> employees = withId(em.createQuery("SELECT e.id, e.employeeName, e.emailAddress, "
                        + "e.designation, e.status FROM Employee e WHERE e.organization.id = :organizationId"
                        + only("e.id", employeeId) + " ORDER BY e.employeeName, e.id", Object[].class), employeeId)
                .setParameter("organizationId", organizationId)
                .getResultList();
        if (employees.isEmpty()) {
            return List.of();
        }
        Map<Long, List<AccessUserRoleDto>> roles = new HashMap<>();
        withId(em.createQuery("SELECT a.employeeId, a.role.id, a.projectId FROM AccessEmployeeRole a "
                        + "WHERE a.organization.id = :organizationId AND a.role.organization.id = :organizationId"
                        + only("a.employeeId", employeeId)
                        + " ORDER BY a.role.name, a.projectId", Object[].class), employeeId)
                .setParameter("organizationId", organizationId)
                .getResultList()
                .forEach(row -> roles.computeIfAbsent((Long) row[0], id -> new ArrayList<>())
                        .add(new AccessUserRoleDto((UUID) row[1], (Long) row[2])));
        Map<Long, List<AccessUserGrantDto>> grants = new HashMap<>();
        withId(em.createQuery("SELECT g FROM AccessEmployeeGrant g WHERE g.organization.id = :organizationId"
                        + only("g.employeeId", employeeId)
                        + " ORDER BY g.permissionKey, g.scope, g.projectId", AccessEmployeeGrant.class), employeeId)
                .setParameter("organizationId", organizationId)
                .getResultList()
                .forEach(g -> grants.computeIfAbsent(g.getEmployeeId(), id -> new ArrayList<>())
                        .add(new AccessUserGrantDto(g.getPermissionKey(), g.getScope().name(), g.getProjectId())));

        return employees.stream()
                .map(row -> {
                    Long id = (Long) row[0];
                    return new AccessUserDto(id, (String) row[1], (String) row[2], (String) row[3],
                            DatabaseAccessFactsSource.carriesAccess((EmployeeStatus) row[4]),
                            roles.getOrDefault(id, List.of()), grants.getOrDefault(id, List.of()));
                })
                .toList();
    }

    private static List<AccessGrantDto> grants(List<AccessRoleGrant> grants) {
        return grants.stream()
                .sorted(Comparator.comparing(AccessRoleGrant::getPermissionKey).thenComparing(g -> g.getScope().name()))
                .map(g -> new AccessGrantDto(g.getPermissionKey(), g.getScope().name()))
                .toList();
    }

    /** The condition that narrows a query to one row's id, or nothing when the whole list is read. */
    private static String only(String column, @Nullable Object id) {
        return id == null ? "" : " AND " + column + " = :id";
    }

    private static <T> TypedQuery<T> withId(TypedQuery<T> query, @Nullable Object id) {
        return id == null ? query : query.setParameter("id", id);
    }
}
