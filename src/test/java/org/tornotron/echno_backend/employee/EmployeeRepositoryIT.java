package org.tornotron.echno_backend.employee;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the paginated employee search against a real CockroachDB
 * (see {@link AbstractIntegrationTest}). The no-filter case is a regression guard:
 * a null search bound inside a SQL {@code CONCAT}/{@code ||} made CockroachDB
 * reject the whole statement (SQLState 22023, "unsupported binary operator:
 * <bytes> || <string>"), so every unfiltered page load 409'd. The pattern is now
 * assembled in the service, so the query must run cleanly with every filter null.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EmployeeRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void search_withEveryFilterNull_executesAndReturnsAll() {
        Organization org = persistOrganization("Org A");
        persistEmployee(org, "Alice");
        persistEmployee(org, "Bob");
        em.flush();
        em.clear();

        // The no-search path is what broke in staging: it must plan and run.
        Page<Employee> result = employeeRepository.search(null, null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Employee::getEmployeeName)
                .contains("Alice", "Bob");
    }

    @Test
    void search_byNamePattern_matchesCaseInsensitively() {
        Organization org = persistOrganization("Org B");
        persistEmployee(org, "Charlotte");
        persistEmployee(org, "Diego");
        em.flush();
        em.clear();

        // Caller lower-cases and wraps the term in wildcards, as EmployeeService does.
        Page<Employee> result = employeeRepository.search("%char%", null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Employee::getEmployeeName)
                .containsExactly("Charlotte");
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

    private void persistEmployee(Organization org, String name) {
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
    }
}
