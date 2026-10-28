package org.tornotron.echno_backend.modules.access.matrix.seed;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessRoleRepository;
import org.tornotron.echno_backend.organization.Organization;

/**
 * Gives an organization the {@link StandardRoles}.
 *
 * <p>Adds only the roles it does not have yet, so running it again changes nothing, and a role
 * the organization has already edited is left exactly as it is. Which roles a new role's holders
 * may grant is filled in after every role exists, since the list can name roles created in the
 * same pass.
 */
@Service
public class StandardRoleSeeder {

    private final AccessRoleRepository roles;

    @PersistenceContext
    private EntityManager em;

    public StandardRoleSeeder(AccessRoleRepository roles) {
        this.roles = roles;
    }

    /** Adds the missing standard roles to the organization; answers how many it added. */
    @Transactional
    public int seed(Long organizationId) {
        Map<String, AccessRole> bySlug = new HashMap<>();
        roles.findAllInOrganization(organizationId).forEach(role -> bySlug.put(role.getSlug(), role));
        Organization organization = em.getReference(Organization.class, organizationId);

        List<StandardRole> missing = StandardRoles.all().stream()
                .filter(template -> !bySlug.containsKey(template.slug()))
                .toList();
        for (StandardRole template : missing) {
            AccessRole role = new AccessRole();
            role.setOrganization(organization);
            role.setSlug(template.slug());
            role.setName(template.name());
            role.setDescription(template.description());
            role.setSystem(true);
            role.setBaseline(template.baseline());
            role.setLegacyRole(template.legacyRole());
            template.grants().forEach(grant -> role.grant(grant.permission(), grant.scope(), grant.locked()));
            roles.save(role);
            bySlug.put(role.getSlug(), role);
        }
        for (StandardRole template : missing) {
            AccessRole role = bySlug.get(template.slug());
            template.grantable().forEach(slug -> role.getGrantableRoles().add(bySlug.get(slug)));
        }
        return missing.size();
    }
}
