package org.tornotron.echno_backend.search;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.issue.Issue;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.enums.TaskStatus;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The quick-search queries against a real CockroachDB (see {@link AbstractIntegrationTest}).
 *
 * <p>These are constructor-expression queries written as JPQL strings, so a wrong field name or a
 * malformed constructor call is invisible until the query runs. A unit test with a mocked
 * repository cannot catch that, which is what this covers: that each query parses, projects into
 * {@link SearchHit}, honours its row limit, and reaches the ids a link needs.
 *
 * <p>Carries the same {@code @DataJpaTest} configuration as the other repository integration tests,
 * so it reuses their context rather than contributing another distinct one to the run's cache.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SearchRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private SearchRepository searchRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void aProjectIsFoundByPartOfItsNameWhateverTheCase() {
        Organization org = persistOrganization("Search Org A");
        persistProject(org, "Riverside Tower A");
        persistProject(org, "Hillside Block B");
        em.flush();
        em.clear();

        List<SearchHit> hits = searchRepository.findProjects("%tower%", PageRequest.ofSize(10));

        assertThat(hits).extracting(SearchHit::title).containsExactly("Riverside Tower A");
        assertThat(hits).allSatisfy(hit -> {
            assertThat(hit.type()).isEqualTo(SearchHitType.PROJECT);
            assertThat(hit.projectId())
                    .as("a project hit points at itself so the palette can link to it")
                    .isEqualTo(hit.id());
        });
    }

    @Test
    void aTaskIsFoundByItsTitleAndCarriesTheProjectItBelongsTo() {
        Organization org = persistOrganization("Search Org B");
        Employee creator = persistEmployee(org, "Bee");
        Project project = persistProject(org, "Riverside");
        persistTask(org, project, creator, "Pour the raft slab");
        persistTask(org, project, creator, "Erect scaffolding");
        em.flush();
        em.clear();

        List<SearchHit> hits = searchRepository.findTasks("%slab%", PageRequest.ofSize(10));

        assertThat(hits).extracting(SearchHit::title).containsExactly("Pour the raft slab");
        assertThat(hits.getFirst().type()).isEqualTo(SearchHitType.TASK);
        assertThat(hits.getFirst().projectId()).isEqualTo(project.getId());
    }

    @Test
    void anIssueIsFoundByItsTitleAndCarriesTheProjectOfItsTask() {
        Organization org = persistOrganization("Search Org C");
        Employee creator = persistEmployee(org, "Cee");
        Project project = persistProject(org, "Hillside");
        Task task = persistTask(org, project, creator, "Inspect the beam");
        persistIssue(org, task, creator, "Crack in the beam");
        persistIssue(org, task, creator, "Missing handrail");
        em.flush();
        em.clear();

        List<SearchHit> hits = searchRepository.findIssues("%crack%", PageRequest.ofSize(10));

        assertThat(hits).extracting(SearchHit::title).containsExactly("Crack in the beam");
        assertThat(hits.getFirst().type()).isEqualTo(SearchHitType.ISSUE);
        assertThat(hits.getFirst().projectId()).isEqualTo(project.getId());
    }

    @Test
    void anIssueWithNoTaskIsStillFound() {
        Organization org = persistOrganization("Search Org D");
        Employee creator = persistEmployee(org, "Dee");
        persistIssue(org, null, creator, "Orphan drainage complaint");
        em.flush();
        em.clear();

        List<SearchHit> hits = searchRepository.findIssues("%drainage%", PageRequest.ofSize(10));

        assertThat(hits).extracting(SearchHit::title)
                .as("an inner join on the task would drop this row silently")
                .containsExactly("Orphan drainage complaint");
        assertThat(hits.getFirst().projectId()).isNull();
    }

    @Test
    void theRowLimitBoundsTheResultRatherThanTheMatchCount() {
        Organization org = persistOrganization("Search Org E");
        for (int i = 1; i <= 12; i++) {
            persistProject(org, "Bounded project " + i);
        }
        em.flush();
        em.clear();

        List<SearchHit> hits = searchRepository.findProjects("%bounded%", PageRequest.ofSize(5));

        assertThat(hits).hasSize(5);
    }

    @Test
    void anEscapedWildcardMatchesItselfRatherThanEverything() {
        Organization org = persistOrganization("Search Org F");
        persistProject(org, "Phase 100% complete");
        persistProject(org, "Phase two");
        em.flush();
        em.clear();

        List<SearchHit> hits = searchRepository.findProjects("%100\\%%", PageRequest.ofSize(10));

        assertThat(hits).extracting(SearchHit::title).containsExactly("Phase 100% complete");
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

    private Employee persistEmployee(Organization org, String name) {
        User user = new User();
        user.setKeycloakId("kc-search-" + name.toLowerCase());
        user.setName(name);
        em.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name.toLowerCase() + "@search.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        em.persist(employee);
        return employee;
    }

    private Project persistProject(Organization org, String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setOrganization(org);
        em.persist(project);
        return project;
    }

    private Task persistTask(Organization org, Project project, Employee creator, String title) {
        Task task = new Task();
        task.setTitle(title);
        task.setOrganization(org);
        task.setCreator(creator);
        task.setProject(project);
        task.setStatus(TaskStatus.upcoming);
        em.persist(task);
        return task;
    }

    private void persistIssue(Organization org, Task task, Employee createdBy, String title) {
        Issue issue = new Issue();
        issue.setTitle(title);
        issue.setDescription(title + " description");
        issue.setType(IssueType.safety);
        issue.setStatus(IssueStatus.open);
        issue.setOrganization(org);
        issue.setCreatedBy(createdBy);
        issue.setTask(task);
        em.persist(issue);
    }
}
