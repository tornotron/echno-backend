package org.tornotron.echno_backend.issue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.enums.TaskStatus;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the paginated issue search against a real CockroachDB
 * (see {@link AbstractIntegrationTest}). Covers the two optional employee filters
 * added for the web all-issues list (web#35 phase 2): {@code assigneeId} narrows
 * to the issues assigned to that employee and {@code creatorId} to the ones they
 * created, while a null argument leaves that clause off.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IssueRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void search_byAssigneeId_returnsOnlyIssuesAssignedToThatEmployee() {
        Organization org = persistOrganization("Org A");
        Employee alice = persistEmployee(org, "Alice");
        Employee bob = persistEmployee(org, "Bob");
        persistIssue(org, "Assigned to Alice", bob, alice);
        persistIssue(org, "Assigned to Bob", bob, bob);
        em.flush();
        em.clear();

        Page<Issue> result = issueRepository.search(
                null, null, null, null, alice.getId(), null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Issue::getTitle)
                .containsExactly("Assigned to Alice");
    }

    @Test
    void search_byCreatorId_returnsOnlyIssuesCreatedByThatEmployee() {
        Organization org = persistOrganization("Org B");
        Employee carol = persistEmployee(org, "Carol");
        Employee dave = persistEmployee(org, "Dave");
        persistIssue(org, "Raised by Carol", carol, dave);
        persistIssue(org, "Raised by Dave", dave, carol);
        em.flush();
        em.clear();

        Page<Issue> result = issueRepository.search(
                null, null, null, null, null, carol.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Issue::getTitle)
                .containsExactly("Raised by Carol");
    }

    @Test
    void search_withBothEmployeeFiltersNull_returnsAll() {
        Organization org = persistOrganization("Org C");
        Employee eve = persistEmployee(org, "Eve");
        persistIssue(org, "First unfiltered", eve, eve);
        persistIssue(org, "Second unfiltered", eve, null);
        em.flush();
        em.clear();

        Page<Issue> result = issueRepository.search(
                null, null, null, null, null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Issue::getTitle)
                .contains("First unfiltered", "Second unfiltered");
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
        user.setKeycloakId("kc-" + name.toLowerCase());
        user.setName(name);
        em.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name.toLowerCase() + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        em.persist(employee);
        return employee;
    }

    private void persistIssue(Organization org, String title, Employee createdBy, Employee assignedTo) {
        Issue issue = new Issue();
        issue.setTitle(title);
        issue.setDescription(title + " description");
        issue.setType(IssueType.safety);
        issue.setStatus(IssueStatus.open);
        issue.setOrganization(org);
        issue.setCreatedBy(createdBy);
        issue.setAssignedTo(assignedTo);
        // The search query resolves i.task.project.id, an implicit inner join, so an
        // issue only appears when it has a task on a project (as every created issue does).
        issue.setTask(persistTask(org, title, createdBy));
        em.persist(issue);
    }

    private Task persistTask(Organization org, String title, Employee creator) {
        Project project = new Project();
        project.setProjectName(title + " project");
        project.setOrganization(org);
        em.persist(project);

        Task task = new Task();
        task.setTitle(title + " task");
        task.setOrganization(org);
        task.setCreator(creator);
        task.setProject(project);
        task.setStatus(TaskStatus.upcoming);
        em.persist(task);
        return task;
    }
}
