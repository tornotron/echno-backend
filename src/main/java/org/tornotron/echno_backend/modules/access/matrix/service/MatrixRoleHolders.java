package org.tornotron.echno_backend.modules.access.matrix.service;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
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
}
