package org.tornotron.echno_backend.modules.access.matrix.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.access.api.AccessRoleAssignments;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessRoleRepository;
import org.tornotron.echno_backend.organization.Organization;

/**
 * Role assignments on the matrix. Every change evicts the person's cached access facts, so it
 * applies on their next request.
 *
 * <p>Queries name the organization explicitly rather than relying on the org filter, because
 * assignments are also made while an organization is being created, before any tenant request.
 */
@Service
public class MatrixRoleAssignments implements AccessRoleAssignments {

    private final AccessRoleRepository roles;
    private final CachedAccessFactsSource facts;

    @PersistenceContext
    private EntityManager em;

    public MatrixRoleAssignments(AccessRoleRepository roles, CachedAccessFactsSource facts) {
        this.roles = roles;
        this.facts = facts;
    }

    @Override
    @Transactional
    public void assign(Long organizationId, Long employeeId, String roleSlug) {
        AccessRole role = role(organizationId, roleSlug);
        if (assignment(employeeId, role) == null) {
            AccessEmployeeRole assignment = new AccessEmployeeRole();
            assignment.setOrganization(em.getReference(Organization.class, organizationId));
            assignment.setEmployeeId(employeeId);
            assignment.setRole(role);
            em.persist(assignment);
        }
        facts.evict(organizationId, employeeId);
    }

    @Override
    @Transactional
    public void remove(Long organizationId, Long employeeId, String roleSlug) {
        AccessRole role = role(organizationId, roleSlug);
        AccessEmployeeRole assignment = assignment(employeeId, role);
        if (assignment != null) {
            em.remove(assignment);
        }
        facts.evict(organizationId, employeeId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> rolesOf(Long organizationId, Long employeeId) {
        return new LinkedHashSet<>(em.createQuery("SELECT a.role.slug FROM AccessEmployeeRole a "
                        + "WHERE a.employeeId = :employeeId AND a.organization.id = :organizationId "
                        + "ORDER BY a.role.slug", String.class)
                .setParameter("employeeId", employeeId)
                .setParameter("organizationId", organizationId)
                .getResultList());
    }

    private AccessRole role(Long organizationId, String roleSlug) {
        return roles.findAllInOrganization(organizationId).stream()
                .filter(role -> role.getSlug().equals(roleSlug))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Organization " + organizationId + " has no role '" + roleSlug + "'"));
    }

    private AccessEmployeeRole assignment(Long employeeId, AccessRole role) {
        return em.createQuery("SELECT a FROM AccessEmployeeRole a "
                        + "WHERE a.employeeId = :employeeId AND a.role = :role", AccessEmployeeRole.class)
                .setParameter("employeeId", employeeId)
                .setParameter("role", role)
                .getResultList().stream().findFirst().orElse(null);
    }
}
