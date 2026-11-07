package org.tornotron.echno_backend.modules.access.matrix.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleTemplate;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleTemplateGrant;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The starting roles against a real CockroachDB: shared by every organization, so the org filter
 * does not hide them, and they load with their grants.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccessRoleTemplateRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private AccessRoleTemplateRepository templates;

    @Autowired
    private TestEntityManager em;

    @AfterEach
    void disableFilter() {
        em.getEntityManager().unwrap(Session.class).disableFilter("orgFilter");
    }

    @Test
    void everyOrganizationReadsTheSameStartingRoles() {
        AccessRoleTemplate template = new AccessRoleTemplate();
        template.setSlug("test-template");
        template.setName("Test Template");
        template.setLegacyOrgRole("project-manager");
        AccessRoleTemplateGrant grant = new AccessRoleTemplateGrant();
        grant.setTemplate(template);
        grant.setPermissionKey("wbs:edit");
        grant.setScope(AccessScope.ASSIGNED_PROJECTS);
        template.getGrants().add(grant);
        em.persist(template);
        Organization org = new AccessFixtures(em.getEntityManager()).organization("Template Org");
        em.flush();
        em.clear();

        em.getEntityManager().unwrap(Session.class)
                .enableFilter("orgFilter").setParameter("organizationId", org.getId());
        List<AccessRoleTemplate> read = templates.findAllWithGrants();

        assertThat(read).filteredOn(t -> t.getSlug().equals("test-template")).singleElement().satisfies(t -> {
            assertThat(t.getLegacyOrgRole()).isEqualTo("project-manager");
            assertThat(t.getGrants()).extracting(AccessRoleTemplateGrant::getPermissionKey)
                    .containsExactly("wbs:edit");
        });
    }
}
