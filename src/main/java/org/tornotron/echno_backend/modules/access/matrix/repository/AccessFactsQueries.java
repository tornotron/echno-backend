package org.tornotron.echno_backend.modules.access.matrix.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.access.api.AccessScope;

/**
 * The reads behind a person's access facts, each one a flat projection rather than a graph of
 * entities, so loading what someone holds is a handful of small queries.
 *
 * <p>Every query names the organization itself instead of relying on the org filter, because
 * facts may be loaded before the tenant filter has run. A query through a role names the role's
 * organization as well as the assignment's, so a role of another organization never counts, even
 * if a row somehow linked one past the table's foreign keys.
 */
@Repository
public class AccessFactsQueries {

    /**
     * One permission and the scope it is held with.
     *
     * @param projectId the project it is held on; null when it holds across the organization
     */
    public record GrantRow(Long projectId, String permissionKey, AccessScope scope) {

        /** A row that holds across the organization. */
        public GrantRow(String permissionKey, AccessScope scope) {
            this(null, permissionKey, scope);
        }
    }

    @PersistenceContext
    private EntityManager em;

    /**
     * The employee's status, when they belong to the organization. Empty when they do not; one
     * element otherwise, which is null for older rows that never had a status set.
     */
    public List<EmployeeStatus> statusOf(Long organizationId, Long employeeId) {
        return em.createQuery("SELECT e.status FROM Employee e "
                        + "WHERE e.id = :employeeId AND e.organization.id = :organizationId", EmployeeStatus.class)
                .setParameter("employeeId", employeeId)
                .setParameter("organizationId", organizationId)
                .getResultList();
    }

    /** The grants of the organization's baseline role, which every active employee holds. */
    public List<GrantRow> baselineGrants(Long organizationId) {
        return em.createQuery("SELECT new " + GrantRow.class.getName() + "(g.permissionKey, g.scope) "
                        + "FROM AccessRole r JOIN r.grants g "
                        + "WHERE r.baseline = true AND r.organization.id = :organizationId", GrantRow.class)
                .setParameter("organizationId", organizationId)
                .getResultList();
    }

    /** The grants of the roles assigned to the employee, each with the assignment's project. */
    public List<GrantRow> roleGrants(Long organizationId, Long employeeId) {
        return em.createQuery("SELECT new " + GrantRow.class.getName() + "(a.projectId, g.permissionKey, g.scope) "
                        + "FROM AccessEmployeeRole a JOIN a.role r JOIN r.grants g "
                        + "WHERE a.employeeId = :employeeId AND a.organization.id = :organizationId "
                        + "AND r.organization.id = :organizationId", GrantRow.class)
                .setParameter("employeeId", employeeId)
                .setParameter("organizationId", organizationId)
                .getResultList();
    }

    /** The grants made to the employee directly, each with its project. */
    public List<GrantRow> directGrants(Long organizationId, Long employeeId) {
        return em.createQuery("SELECT new " + GrantRow.class.getName() + "(g.projectId, g.permissionKey, g.scope) "
                        + "FROM AccessEmployeeGrant g "
                        + "WHERE g.employeeId = :employeeId AND g.organization.id = :organizationId", GrantRow.class)
                .setParameter("employeeId", employeeId)
                .setParameter("organizationId", organizationId)
                .getResultList();
    }

    /** The projects the employee is a member of. */
    public List<Long> projectIds(Long organizationId, Long employeeId) {
        return em.createQuery("SELECT p.id FROM Project p JOIN p.employees e "
                        + "WHERE e.id = :employeeId AND p.organization.id = :organizationId", Long.class)
                .setParameter("employeeId", employeeId)
                .setParameter("organizationId", organizationId)
                .getResultList();
    }

    public List<UUID> customerIds(Long organizationId, Long employeeId) {
        return em.createQuery("SELECT l.customerId FROM AccessPartyLink l "
                        + "WHERE l.employeeId = :employeeId AND l.organization.id = :organizationId "
                        + "AND l.customerId IS NOT NULL", UUID.class)
                .setParameter("employeeId", employeeId)
                .setParameter("organizationId", organizationId)
                .getResultList();
    }

    public List<Long> vendorIds(Long organizationId, Long employeeId) {
        return em.createQuery("SELECT l.vendorId FROM AccessPartyLink l "
                        + "WHERE l.employeeId = :employeeId AND l.organization.id = :organizationId "
                        + "AND l.vendorId IS NOT NULL", Long.class)
                .setParameter("employeeId", employeeId)
                .setParameter("organizationId", organizationId)
                .getResultList();
    }
}
