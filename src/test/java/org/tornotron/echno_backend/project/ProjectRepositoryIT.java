package org.tornotron.echno_backend.project;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The paginated project search against a real CockroachDB (see {@link AbstractIntegrationTest}).
 *
 * <p>{@link ProjectServicePaginationTest} covers the clamping and the pattern building with a
 * mocked repository, and by construction cannot catch anything about the query itself. This is
 * written as JPQL, so a wrong field name or a mishandled {@code ESCAPE} clause is invisible until
 * it runs against a database.
 *
 * <p>Carries the same {@code @DataJpaTest} configuration as the other repository integration tests,
 * so it reuses their context rather than contributing another distinct one to the run's cache.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProjectRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TestEntityManager em;

    private static final PageRequest TEN = PageRequest.ofSize(10);

    @Test
    void aNullSearchReturnsEveryProjectAndAPatternNarrowsIt() {
        Organization org = persistOrganization("Project Repo Org A");
        persistProject(org, "Riverside Tower");
        persistProject(org, "Hillside Block");
        em.flush();
        em.clear();

        Page<Project> everything = projectRepository.search(null, TEN);
        Page<Project> matching = projectRepository.search("%tower%", TEN);

        assertThat(everything.getContent())
                .as("a null search must drop its clause rather than match nothing")
                .extracting(Project::getProjectName)
                .contains("Riverside Tower", "Hillside Block");
        assertThat(matching.getContent())
                .extracting(Project::getProjectName)
                .containsExactly("Riverside Tower");
    }

    @Test
    void theResultIsBoundedByTheLimitAndTheTotalStillReportsEveryMatch() {
        Organization org = persistOrganization("Project Repo Org B");
        for (int i = 1; i <= 12; i++) {
            persistProject(org, "Bounded project " + i);
        }
        em.flush();
        em.clear();

        Page<Project> page = projectRepository.search("%bounded%", PageRequest.ofSize(5));

        assertThat(page.getContent()).hasSize(5);
        assertThat(page.getTotalElements())
                .as("the page envelope is the whole point: a short page must still say how many exist")
                .isEqualTo(12);
    }

    @Test
    void anEscapedWildcardMatchesItselfRatherThanEverything() {
        Organization org = persistOrganization("Project Repo Org C");
        persistProject(org, "Phase 100% complete");
        persistProject(org, "Phase two");
        em.flush();
        em.clear();

        Page<Project> page = projectRepository.search("%100\\%%", TEN);

        assertThat(page.getContent())
                .as("an unescaped per-cent sign would match every project")
                .extracting(Project::getProjectName)
                .containsExactly("Phase 100% complete");
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

    private void persistProject(Organization org, String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setOrganization(org);
        em.persist(project);
    }
}
