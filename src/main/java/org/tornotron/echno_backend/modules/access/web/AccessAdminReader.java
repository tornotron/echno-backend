package org.tornotron.echno_backend.modules.access.web;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeGrant;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessGroup;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessGroupGrant;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrant;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGroupDto;
import org.tornotron.echno_backend.modules.access.matrix.service.DatabaseAccessFactsSource;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserDto;

/**
 * What the Roles &amp; Permissions screen reads, assembled in a few queries per list rather than a
 * query per row. Every query names the organization itself.
 */
@Service
public class AccessAdminReader {

    @PersistenceContext
    private EntityManager em;

    /** The organization's roles, the baseline first and the rest by name. */
    @Transactional(readOnly = true)
    public List<AccessRoleDto> roles(Long organizationId) {
        List<AccessRole> roles = em.createQuery("SELECT DISTINCT r FROM AccessRole r LEFT JOIN FETCH r.grants "
                        + "WHERE r.organization.id = :organizationId", AccessRole.class)
                .setParameter("organizationId", organizationId)
                .getResultList();
        Map<UUID, List<Long>> members = pairs("SELECT a.role.id, a.employeeId FROM AccessEmployeeRole a "
                + "WHERE a.organization.id = :organizationId ORDER BY a.employeeId", organizationId);
        Map<UUID, List<UUID>> grantable = pairs("SELECT r.id, g.id FROM AccessRole r JOIN r.grantableRoles g "
                + "WHERE r.organization.id = :organizationId ORDER BY g.slug", organizationId);

        return roles.stream()
                .sorted(Comparator.comparing(AccessRole::isBaseline).reversed()
                        .thenComparing(AccessRole::getName, String.CASE_INSENSITIVE_ORDER))
                .map(role -> new AccessRoleDto(role.getId(), role.getSlug(), role.getName(), role.getSubtitle(),
                        role.getDescription(), role.isSystem(), role.isBaseline(), grants(role.getGrants()),
                        members.getOrDefault(role.getId(), List.of()),
                        grantable.getOrDefault(role.getId(), List.of())))
                .toList();
    }

    /** The organization's departments and groups, by name. */
    @Transactional(readOnly = true)
    public List<AccessGroupDto> groups(Long organizationId) {
        List<AccessGroup> groups = em.createQuery("SELECT DISTINCT g FROM AccessGroup g LEFT JOIN FETCH g.grants "
                        + "WHERE g.organization.id = :organizationId", AccessGroup.class)
                .setParameter("organizationId", organizationId)
                .getResultList();
        Map<UUID, List<Long>> members = pairs("SELECT g.id, m FROM AccessGroup g JOIN g.memberIds m "
                + "WHERE g.organization.id = :organizationId ORDER BY m", organizationId);
        Map<UUID, List<UUID>> roles = pairs("SELECT g.id, r.id FROM AccessGroup g JOIN g.roles r "
                + "WHERE g.organization.id = :organizationId ORDER BY r.name", organizationId);

        return groups.stream()
                .sorted(Comparator.comparing(AccessGroup::getName, String.CASE_INSENSITIVE_ORDER))
                .map(group -> new AccessGroupDto(group.getId(), group.getKind().name(), group.getName(),
                        group.getDescription(), members.getOrDefault(group.getId(), List.of()),
                        roles.getOrDefault(group.getId(), List.of()), groupGrants(group.getGrants())))
                .toList();
    }

    /**
     * Every employee of the organization, by name, with what is given to them directly. The whole
     * organization at once, as the screen's people tab lists it; paging comes when an
     * organization's size needs it.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<AccessUserDto> users(Long organizationId) {
        List<Object[]> employees = em.createQuery("SELECT e.id, e.employeeName, e.emailAddress, e.designation, "
                        + "e.status FROM Employee e WHERE e.organization.id = :organizationId "
                        + "ORDER BY e.employeeName, e.id")
                .setParameter("organizationId", organizationId)
                .getResultList();
        Map<Long, List<UUID>> roles = pairs("SELECT a.employeeId, a.role.id FROM AccessEmployeeRole a "
                + "WHERE a.organization.id = :organizationId ORDER BY a.role.name", organizationId);
        Map<Long, List<UUID>> groups = pairs("SELECT m, g.id FROM AccessGroup g JOIN g.memberIds m "
                + "WHERE g.organization.id = :organizationId ORDER BY g.name", organizationId);
        Map<Long, List<AccessGrantDto>> grants = new HashMap<>();
        em.createQuery("SELECT g FROM AccessEmployeeGrant g WHERE g.organization.id = :organizationId "
                        + "ORDER BY g.permissionKey, g.scope", AccessEmployeeGrant.class)
                .setParameter("organizationId", organizationId)
                .getResultList()
                .forEach(g -> grants.computeIfAbsent(g.getEmployeeId(), id -> new ArrayList<>())
                        .add(new AccessGrantDto(g.getPermissionKey(), g.getScope().name(), false)));

        return employees.stream()
                .map(row -> {
                    Long id = (Long) row[0];
                    return new AccessUserDto(id, (String) row[1], (String) row[2], (String) row[3],
                            DatabaseAccessFactsSource.carriesAccess((EmployeeStatus) row[4]),
                            roles.getOrDefault(id, List.of()), groups.getOrDefault(id, List.of()),
                            grants.getOrDefault(id, List.of()));
                })
                .toList();
    }

    private static List<AccessGrantDto> groupGrants(List<AccessGroupGrant> grants) {
        return grants.stream()
                .sorted(Comparator.comparing(AccessGroupGrant::getPermissionKey).thenComparing(g -> g.getScope().name()))
                .map(g -> new AccessGrantDto(g.getPermissionKey(), g.getScope().name(), false))
                .toList();
    }

    private static List<AccessGrantDto> grants(List<AccessRoleGrant> grants) {
        return grants.stream()
                .sorted(Comparator.comparing(AccessRoleGrant::getPermissionKey).thenComparing(g -> g.getScope().name()))
                .map(g -> new AccessGrantDto(g.getPermissionKey(), g.getScope().name(), g.isLocked()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private <K, V> Map<K, List<V>> pairs(String query, Long organizationId) {
        Map<K, List<V>> grouped = new HashMap<>();
        for (Object[] row : (List<Object[]>) em.createQuery(query).setParameter("organizationId", organizationId)
                .getResultList()) {
            grouped.computeIfAbsent((K) row[0], key -> new ArrayList<>()).add((V) row[1]);
        }
        return grouped;
    }
}
