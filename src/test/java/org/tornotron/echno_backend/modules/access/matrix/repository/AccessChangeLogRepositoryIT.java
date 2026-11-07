package org.tornotron.echno_backend.modules.access.matrix.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessChangeLog;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The change history against a real CockroachDB: a row keeps its before and after state, stays
 * inside its organization, and outlives what it talks about.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccessChangeLogRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private AccessChangeLogRepository log;

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
    void aRowKeepsItsStateAndOutlivesTheRoleItTalksAbout() {
        Organization org = fixtures.organization("Log Org A");
        AccessRole role = fixtures.role(org, "site-engineer");
        String target = role.getId().toString();
        log.save(AccessChangeLog.of(org, 11L, "role-renamed", "access-role", target,
                Map.of("name", "Site Engineer"), Map.of("name", "Engineer")));
        em.flush();

        em.getEntityManager().createNativeQuery("DELETE FROM access_role WHERE id = ?1")
                .setParameter(1, role.getId())
                .executeUpdate();
        em.clear();

        assertThat(log.findByTarget("access-role", target)).singleElement().satisfies(row -> {
            assertThat(row.getOrganization().getId()).isEqualTo(org.getId());
            assertThat(row.getActorUserId()).isEqualTo(11L);
            assertThat(row.getAction()).isEqualTo("role-renamed");
            assertThat(row.getBeforeState()).containsEntry("name", "Site Engineer");
            assertThat(row.getAfterState()).containsEntry("name", "Engineer");
            assertThat(row.getChangedAt()).isNotNull();
        });
    }

    @Test
    void theOrgFilterHidesAnotherOrganizationsHistory() {
        Organization mine = fixtures.organization("Log Org B");
        Organization theirs = fixtures.organization("Log Org C");
        log.save(AccessChangeLog.of(theirs, null, "role-created", "access-role", "shared-target", null, Map.of()));
        em.flush();
        em.clear();

        scopeTo(mine);

        assertThat(log.findByTarget("access-role", "shared-target")).isEmpty();
    }
}
