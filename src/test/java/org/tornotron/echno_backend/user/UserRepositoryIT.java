package org.tornotron.echno_backend.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the org-scoped user queries against a real CockroachDB
 * (see {@link AbstractIntegrationTest}). These queries are the mechanism that
 * scopes user reads and batch updates to a tenant, so they are covered with real
 * SQL rather than mocks.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void findUsersByOrganizationId_executesAndReturnsEmpty_forAnUnknownOrg() {
        Page<User> result = userRepository.findUsersByOrganizationId(999_999L, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void findUsersByOrganizationIdAndIdIn_returnsOnlyMembersOfThatOrganization() {
        Organization orgA = persistOrganization("Org A");
        Organization orgB = persistOrganization("Org B");

        User a1 = persistUser("a1");
        User a2 = persistUser("a2");
        User b1 = persistUser("b1");
        persistEmployee(orgA, a1);
        persistEmployee(orgA, a2);
        persistEmployee(orgB, b1);
        em.flush();
        em.clear();

        List<User> found = userRepository.findUsersByOrganizationIdAndIdIn(
                orgA.getId(), List.of(a1.getId(), a2.getId(), b1.getId()));

        // b1 belongs to Org B: even though its id was requested, it must not come
        // back, so the batch-update caller rejects it instead of editing it.
        assertThat(found).extracting(User::getId)
                .containsExactlyInAnyOrder(a1.getId(), a2.getId());
    }

    @Test
    void findDisplayNamesByIdIn_resolvesAUserWhoHasNoEmployeeRecord() {
        // The stamp on a document is a user id, so the name has to come from the user row.
        // Resolving it through the employee lookup, as the web app used to, misses exactly this
        // account: a user with no employment, which is the shape a QA raiser or a bootstrap
        // administrator has.
        User withoutEmployment = persistUser("raiser");
        em.flush();
        em.clear();

        List<UserDisplayName> found =
                userRepository.findDisplayNamesByIdIn(List.of(withoutEmployment.getId()));

        assertThat(found).singleElement()
                .extracting(UserDisplayName::name).isEqualTo("raiser");
    }

    @Test
    void findDisplayNamesByIdIn_isNotScopedToAnOrganization() {
        // A document outlives the approver's employment. Scoping the name read would blank the
        // approver on exactly the historical records the stamp exists for.
        Organization orgA = persistOrganization("Org C");
        User member = persistUser("member");
        User outsider = persistUser("outsider");
        persistEmployee(orgA, member);
        em.flush();
        em.clear();

        List<UserDisplayName> found = userRepository.findDisplayNamesByIdIn(
                List.of(member.getId(), outsider.getId()));

        assertThat(found).extracting(UserDisplayName::id)
                .containsExactlyInAnyOrder(member.getId(), outsider.getId());
    }

    @Test
    void findDisplayNamesByIdIn_omitsAnIdWithNoRow() {
        User present = persistUser("present");
        em.flush();
        em.clear();

        List<UserDisplayName> found =
                userRepository.findDisplayNamesByIdIn(List.of(present.getId(), 999_999L));

        assertThat(found).extracting(UserDisplayName::id).containsExactly(present.getId());
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

    private User persistUser(String tag) {
        User user = new User();
        user.setKeycloakId("kc-" + tag);
        user.setName(tag);
        em.persist(user);
        return user;
    }

    private Employee persistEmployee(Organization org, User user) {
        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(user.getName());
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(user.getName() + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        em.persist(employee);
        return employee;
    }
}
