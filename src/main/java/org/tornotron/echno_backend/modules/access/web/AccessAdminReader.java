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
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrant;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleDto;

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
