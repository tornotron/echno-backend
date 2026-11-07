package org.tornotron.echno_backend.modules.access.matrix.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrant;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrantable;
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
    private AccessRoleGrantableRepository grantable;

    @Autowired
    private TestEntityManager em;

    private AccessFixtures fixtures;

    @BeforeEach
    void fixtures() {
        fixtures = new AccessFixtures(em.getEntityManager());
    }

    @AfterEach
    void disableFilter() {
        em.getEntityManager().unwrap(Session.class).disableFilter("orgFilter");
    }

    private void scopeTo(Organization org) {
        em.getEntityManager().unwrap(Session.class)
                .enableFilter("orgFilter").setParameter("organizationId", org.getId());
    }

    @Test
    void rolesLoadWithTheirGrantsAndOnlyForTheCurrentOrganization() {
        Organization mine = fixtures.organization("Access Org A");
        Organization theirs = fixtures.organization("Access Org B");
        AccessRole manager = fixtures.newRole(mine, "project-manager");
        manager.grant(AccessPermission.of("wbs:edit"), AccessScope.ASSIGNED_PROJECTS);
        manager.grant(AccessPermission.of("leave:read"), AccessScope.OWN);
        em.persist(manager);
        fixtures.role(theirs, "project-manager");
        em.flush();
        em.clear();

        scopeTo(mine);

        assertThat(roles.findAllWithGrants()).singleElement().satisfies(role -> {
            assertThat(role.getSlug()).isEqualTo("project-manager");
            assertThat(role.getGrants()).extracting(AccessRoleGrant::getPermissionKey)
                    .containsExactlyInAnyOrder("wbs:edit", "leave:read");
        });
        assertThat(roles.findBySlugScoped("project-manager")).isPresent();
    }

    @Test
    void theSlugIsUniqueInAnOrganization() {
        Organization org = fixtures.organization("Access Org C");
        AccessRole first = fixtures.newRole(org, "store-keeper");
        first.setName("Store Keeper");
        em.persist(first);
        AccessRole second = fixtures.newRole(org, "store-keeper");
        second.setName("Stores");
        em.persist(second);

        assertThatThrownBy(() -> em.flush()).hasStackTraceContaining("uq_access_role_slug");
    }

    @Test
    void theNameIsUniqueInAnOrganizationIgnoringCase() {
        Organization org = fixtures.organization("Access Org D");
        AccessRole first = fixtures.newRole(org, "site-lead");
        first.setName("Site Lead");
        em.persist(first);
        AccessRole second = fixtures.newRole(org, "site-lead-2");
        second.setName("SITE LEAD");
        em.persist(second);

        assertThatThrownBy(() -> em.flush()).hasStackTraceContaining("uq_access_role_name");
    }

    @Test
    void twoOrganizationsMayUseTheSameName() {
        AccessRole first = fixtures.newRole(fixtures.organization("Access Org E"), "site-lead");
        AccessRole second = fixtures.newRole(fixtures.organization("Access Org F"), "site-lead");
        em.persist(first);
        em.persist(second);

        em.flush();

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void anOrganizationHasOneBaselineRole() {
        Organization org = fixtures.organization("Access Org G");
        AccessRole first = fixtures.newRole(org, "employee");
        first.setBaseline(true);
        em.persist(first);
        AccessRole second = fixtures.newRole(org, "member");
        second.setBaseline(true);
        em.persist(second);

        assertThatThrownBy(() -> em.flush()).hasStackTraceContaining("uq_access_role_baseline");
    }

    @Test
    void deletingARoleDeletesItsGrants() {
        Organization org = fixtures.organization("Access Org H");
        AccessRole role = fixtures.newRole(org, "hr-admin");
        role.grant(AccessPermission.of("team:edit"), AccessScope.ALL);
        em.persist(role);
        em.flush();

        roles.delete(role);
        em.flush();

        assertThat(em.getEntityManager()
                .createQuery("SELECT COUNT(g) FROM AccessRoleGrant g WHERE g.role.id = :id", Long.class)
                .setParameter("id", role.getId())
                .getSingleResult()).isZero();
    }

    @Test
    void aRoleRemembersWhichRolesItsHoldersMayAssign() {
        Organization org = fixtures.organization("Access Org I");
        AccessRole siteEngineer = fixtures.role(org, "site-engineer");
        fixtures.role(org, "system-admin");
        AccessRole hr = fixtures.role(org, "hr-admin");
        AccessRoleGrantable link = new AccessRoleGrantable();
        link.setOrganization(org);
        link.setRole(hr);
        link.setGrantableRole(siteEngineer);
        em.persist(link);
        em.flush();
        em.clear();

        scopeTo(org);

        assertThat(grantable.findByRole(hr.getId()))
                .extracting(found -> found.getGrantableRole().getSlug())
                .containsExactly("site-engineer");
    }
}
