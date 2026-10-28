package org.tornotron.echno_backend.modules.access.matrix.seed;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.access.api.AccessProvisioning;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessRoleRepository;
import org.tornotron.echno_backend.modules.access.matrix.service.CachedAccessFactsSource;
import org.tornotron.echno_backend.organization.Organization;

/**
 * Provisions a new organization on the matrix: the standard roles, and the owner as company
 * administrator.
 */
@Service
public class MatrixAccessProvisioning implements AccessProvisioning {

    private final StandardRoleSeeder seeder;
    private final AccessRoleRepository roles;
    private final CachedAccessFactsSource facts;

    @PersistenceContext
    private EntityManager em;

    public MatrixAccessProvisioning(StandardRoleSeeder seeder, AccessRoleRepository roles,
                                    CachedAccessFactsSource facts) {
        this.seeder = seeder;
        this.roles = roles;
        this.facts = facts;
    }

    @Override
    @Transactional
    public void provisionOrganization(Long organizationId, Long ownerEmployeeId) {
        seeder.seed(organizationId);
        AccessRole admin = roles.findAllInOrganization(organizationId).stream()
                .filter(role -> StandardRoles.SYSTEM_ADMIN.equals(role.getSlug()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Organization " + organizationId + " has no " + StandardRoles.SYSTEM_ADMIN + " role"));
        boolean alreadyAdmin = !em.createQuery("SELECT a.id FROM AccessEmployeeRole a "
                        + "WHERE a.employeeId = :employeeId AND a.role = :role", Object.class)
                .setParameter("employeeId", ownerEmployeeId)
                .setParameter("role", admin)
                .getResultList().isEmpty();
        if (!alreadyAdmin) {
            AccessEmployeeRole assignment = new AccessEmployeeRole();
            assignment.setOrganization(em.getReference(Organization.class, organizationId));
            assignment.setEmployeeId(ownerEmployeeId);
            assignment.setRole(admin);
            em.persist(assignment);
        }
        facts.evict(organizationId, ownerEmployeeId);
    }
}
