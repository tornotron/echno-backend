package org.tornotron.echno_backend.modules.access.matrix.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrant;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessRoleRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * Seeding the standard roles against a real CockroachDB: every role with its grants and
 * grantable roles lands, a second run adds nothing, an organization's edits survive, and
 * organizations do not share roles.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StandardRoleSeeder.class)
class StandardRoleSeederIT extends AbstractIntegrationTest {

    @Autowired
    private StandardRoleSeeder seeder;

    @Autowired
    private AccessRoleRepository roles;

    @Autowired
    private TestEntityManager em;

    @Test
    void aNewOrganizationGetsEveryStandardRole() {
        Organization org = persistOrganization("Seed Org A");

        int added = seeder.seed(org.getId());
        em.flush();
        em.clear();

        assertThat(added).isEqualTo(StandardRoles.all().size());
        assertThat(roles.findAllInOrganization(org.getId())).extracting(AccessRole::getSlug)
                .containsExactlyInAnyOrderElementsOf(StandardRoles.all().stream().map(StandardRole::slug).toList());

        AccessRole member = roleIn(org, StandardRoles.MEMBER);
        assertThat(member.isBaseline()).isTrue();
        assertThat(member.isSystem()).isTrue();
        assertThat(member.getGrants()).filteredOn(AccessRoleGrant::isLocked)
                .extracting(AccessRoleGrant::getPermissionKey).contains("leave:create");

        AccessRole hr = roleIn(org, "hr-admin");
        assertThat(hr.getLegacyRole()).isEqualTo("hr-admin");
        assertThat(hr.getGrantableRoles()).extracting(AccessRole::getSlug)
                .contains("site-engineer").doesNotContain(StandardRoles.SYSTEM_ADMIN);
    }

    @Test
    void seedingAgainAddsNothingAndKeepsTheOrganizationsEdits() {
        Organization org = persistOrganization("Seed Org B");
        seeder.seed(org.getId());
        em.flush();
        AccessRole keeper = roleIn(org, "store-keeper");
        keeper.setName("Stores");
        keeper.revoke(AccessPermission.of("vendors:view"), AccessScope.ALL);
        em.flush();
        em.clear();

        int added = seeder.seed(org.getId());
        em.flush();
        em.clear();

        assertThat(added).isZero();
        AccessRole reloaded = roleIn(org, "store-keeper");
        assertThat(reloaded.getName()).isEqualTo("Stores");
        assertThat(reloaded.getGrants()).extracting(AccessRoleGrant::getPermissionKey).doesNotContain("vendors:view");
    }

    @Test
    void organizationsDoNotShareRoles() {
        Organization first = persistOrganization("Seed Org C");
        Organization second = persistOrganization("Seed Org D");

        seeder.seed(first.getId());
        seeder.seed(second.getId());
        em.flush();
        em.clear();

        assertThat(roles.findAllInOrganization(first.getId())).hasSize(StandardRoles.all().size());
        assertThat(roles.findAllInOrganization(second.getId())).hasSize(StandardRoles.all().size())
                .allSatisfy(role -> assertThat(role.getOrganization().getId()).isEqualTo(second.getId()));
    }

    private AccessRole roleIn(Organization org, String slug) {
        return roles.findAllInOrganization(org.getId()).stream()
                .filter(role -> role.getSlug().equals(slug))
                .findFirst().orElseThrow();
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
