package org.tornotron.echno_backend.modules.access.matrix.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrant;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The role tables against a real CockroachDB: the changelog matches the entities, grants load
 * with their role, the org filter hides another organization's roles, and the unique keys hold.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccessRoleRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private AccessRoleRepository roles;

    @Autowired
    private TestEntityManager em;

    @AfterEach
    void disableFilter() {
        em.getEntityManager().unwrap(Session.class).disableFilter("orgFilter");
    }

    @Test
    void rolesLoadWithTheirGrantsAndOnlyForTheCurrentOrganization() {
        Organization mine = persistOrganization("Access Org A");
        Organization theirs = persistOrganization("Access Org B");
        AccessRole manager = role(mine, "project-manager", "Project Manager");
        manager.grant(AccessPermission.of("wbs:edit"), AccessScope.ASSIGNED_PROJECTS, false);
        manager.grant(AccessPermission.of("leave:view"), AccessScope.OWN, true);
        em.persist(manager);
        em.persist(role(theirs, "project-manager", "Project Manager"));
        em.flush();
        em.clear();

        scopeTo(mine);

        assertThat(roles.findAllWithGrants()).singleElement().satisfies(role -> {
            assertThat(role.getSlug()).isEqualTo("project-manager");
            assertThat(role.getGrants()).extracting(AccessRoleGrant::getPermissionKey)
                    .containsExactlyInAnyOrder("wbs:edit", "leave:view");
        });
        assertThat(roles.findBySlugScoped("project-manager")).isPresent();
    }

    @Test
    void theSlugIsUniqueInAnOrganization() {
        Organization org = persistOrganization("Access Org C");
        em.persist(role(org, "store-keeper", "Store Keeper"));
        em.persist(role(org, "store-keeper", "Stores"));

        assertThatThrownBy(() -> em.flush()).isInstanceOf(Exception.class);
    }

    @Test
    void deletingARoleDeletesItsGrants() {
        Organization org = persistOrganization("Access Org D");
        AccessRole role = role(org, "hr-admin", "HR Admin");
        role.grant(AccessPermission.of("team:edit"), AccessScope.ALL, false);
        em.persist(role);
        em.flush();

        roles.delete(role);
        em.flush();

        assertThat(em.getEntityManager()
                .createQuery("SELECT COUNT(g) FROM AccessRoleGrant g", Long.class)
                .getSingleResult()).isZero();
    }

    private AccessRole role(Organization org, String slug, String name) {
        AccessRole role = new AccessRole();
        role.setOrganization(org);
        role.setSlug(slug);
        role.setName(name);
        return role;
    }

    private void scopeTo(Organization org) {
        em.getEntityManager().unwrap(Session.class)
                .enableFilter("orgFilter").setParameter("organizationId", org.getId());
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }
}
