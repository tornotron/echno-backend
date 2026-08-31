package org.tornotron.echno_backend.issue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Integration tests for the issue title against a real CockroachDB, so the schema's own
 * constraints are what is under test (issue #614).
 *
 * <p>An issue title used to be unique across every organization at once, under
 * {@code uk_issue_title}. Two tenants could therefore not both raise "Leak in basement", and
 * because nothing in {@link IssueService} checks for a duplicate first, the second tenant's
 * create came back as a constraint violation over rows they cannot see.
 *
 * <p>Migration 076 removes the constraint rather than rescoping it to the organization. A title
 * is a description rather than a reference, nothing looks an issue up by it, and one project
 * genuinely raises the same finding against many tasks, so both of the cases pinned here have
 * to be accepted: the same title in another organization, and the same title twice in one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IssueTitleUniquenessIT extends AbstractIntegrationTest {

    private static final String SHARED_TITLE = "Leak in basement";

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void sameTitleInAnotherOrganization_isAccepted() {
        Organization first = persistOrganization("Issue Title Org One");
        Organization second = persistOrganization("Issue Title Org Two");
        persistIssue(first, SHARED_TITLE, persistEmployee(first, "raiser-one"));

        assertThatNoException().isThrownBy(() -> {
            persistIssue(second, SHARED_TITLE, persistEmployee(second, "raiser-two"));
            em.flush();
        });

        assertThat(issueRepository.findAll())
                .filteredOn(issue -> SHARED_TITLE.equals(issue.getTitle()))
                .hasSize(2);
    }

    @Test
    void sameTitleTwiceInOneOrganization_isAccepted() {
        Organization org = persistOrganization("Issue Title Org Three");
        Employee raiser = persistEmployee(org, "raiser-three");
        persistIssue(org, "Tiles not level", raiser);

        assertThatNoException().isThrownBy(() -> {
            persistIssue(org, "Tiles not level", raiser);
            em.flush();
        });
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "-").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }

    private Employee persistEmployee(Organization org, String handle) {
        User user = new User();
        user.setKeycloakId("kc-issue-title-" + handle);
        user.setName(handle);
        em.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(handle);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress("issue-title-" + handle + "@example.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        em.persist(employee);
        return employee;
    }

    private void persistIssue(Organization org, String title, Employee createdBy) {
        Issue issue = new Issue();
        issue.setTitle(title);
        issue.setDescription(title + " description");
        issue.setType(IssueType.safety);
        issue.setStatus(IssueStatus.open);
        issue.setOrganization(org);
        issue.setCreatedBy(createdBy);
        em.persist(issue);
    }
}
