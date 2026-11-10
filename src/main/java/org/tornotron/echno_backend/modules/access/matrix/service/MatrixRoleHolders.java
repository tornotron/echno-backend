package org.tornotron.echno_backend.modules.access.matrix.service;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.tornotron.echno_backend.modules.access.api.AccessRoleHolders;

/**
 * Reads role holders from the assignments. A role counts when it was made from the template of that
 * slug ({@code createdBy} is null), and the assignment, the role and the employee all belong to the
 * organization asked about, which every query names itself so the answer holds outside a request too.
 * Through the persistence context, so a change the caller has not flushed yet is seen.
 */
@Service
public class MatrixRoleHolders implements AccessRoleHolders {

    private static final String HOLDERS = "SELECT DISTINCT a.employeeId FROM AccessEmployeeRole a JOIN a.role r "
            + "JOIN AccessRoleTemplate t ON t.slug = r.slug "
            + "JOIN Employee e ON e.id = a.employeeId ";

    private static final String SAME_ORGANIZATION = "a.organization.id = :organization "
            + "AND r.organization.id = :organization AND e.organization.id = :organization "
            + "AND r.createdBy IS NULL ";

    /** The transaction resource holding the old roles read so far, by organization, then employee. */
    private static final Object OLD_ROLES = new Object();

    private final EntityManager em;

    public MatrixRoleHolders(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<Long> acrossOrganization(Long organizationId, Collection<String> roles) {
        if (roles.isEmpty()) {
            return List.of();
        }
        return em.createQuery(HOLDERS + "WHERE " + SAME_ORGANIZATION
                        + "AND a.projectId IS NULL AND t.slug IN :roles ORDER BY a.employeeId", Long.class)
                .setParameter("organization", organizationId)
                .setParameter("roles", roles)
                .getResultList();
    }

    @Override
    public List<Long> onProject(Long organizationId, Long projectId, String role) {
        return em.createQuery(HOLDERS + "JOIN e.projects p WHERE " + SAME_ORGANIZATION
                        + "AND p.id = :project AND (a.projectId IS NULL OR a.projectId = :project) "
                        + "AND t.slug = :role ORDER BY a.employeeId", Long.class)
                .setParameter("organization", organizationId)
                .setParameter("project", projectId)
                .setParameter("role", role)
                .getResultList();
    }

    /**
     * Within a transaction, the old roles of the whole organization are read once and kept until it ends,
     * so a page that maps a hundred employees reads them in one query, as the batch-fetched
     * {@code employee_org_roles} collection did. An assignment change in the same transaction forgets
     * them (see {@link #forgetOldRoles}), so a read after it sees the change.
     */
    @Override
    public Set<String> oldRolesOf(Long organizationId, Long employeeId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return oldRoles(organizationId, employeeId).getOrDefault(employeeId, Set.of());
        }
        @SuppressWarnings("unchecked")
        Map<Long, Map<Long, Set<String>>> byOrganization =
                (Map<Long, Map<Long, Set<String>>>) TransactionSynchronizationManager.getResource(OLD_ROLES);
        if (byOrganization == null) {
            byOrganization = new HashMap<>();
            TransactionSynchronizationManager.bindResource(OLD_ROLES, byOrganization);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(OLD_ROLES);
                }
            });
        }
        return byOrganization.computeIfAbsent(organizationId, organization -> oldRoles(organization, null))
                .getOrDefault(employeeId, Set.of());
    }

    /** Drops what this transaction has read, after an assignment that changes someone's old roles. */
    static void forgetOldRoles() {
        Object read = TransactionSynchronizationManager.getResource(OLD_ROLES);
        if (read instanceof Map<?, ?> byOrganization) {
            byOrganization.clear();
        }
    }

    /** The old roles of one employee, or of everyone in the organization when none is named. */
    private Map<Long, Set<String>> oldRoles(Long organizationId, Long employeeId) {
        var query = em.createQuery("SELECT DISTINCT a.employeeId, t.legacyOrgRole FROM AccessEmployeeRole a "
                        + "JOIN a.role r JOIN AccessRoleTemplate t ON t.slug = r.slug "
                        + "JOIN Employee e ON e.id = a.employeeId WHERE " + SAME_ORGANIZATION
                        + (employeeId == null ? "" : "AND a.employeeId = :employee ")
                        + "AND a.projectId IS NULL AND t.legacyOrgRole IS NOT NULL", Object[].class)
                .setParameter("organization", organizationId);
        if (employeeId != null) {
            query.setParameter("employee", employeeId);
        }
        List<Object[]> rows = query.getResultList();
        Map<Long, Set<String>> held = new HashMap<>();
        for (Object[] row : rows) {
            held.computeIfAbsent((Long) row[0], employee -> new HashSet<>()).add((String) row[1]);
        }
        return held;
    }
}
