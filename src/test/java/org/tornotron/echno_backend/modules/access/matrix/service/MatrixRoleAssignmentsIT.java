package org.tornotron.echno_backend.modules.access.matrix.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessRoleAssignments;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessFactsQueries;
import org.tornotron.echno_backend.modules.access.matrix.seed.StandardRoleSeeder;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

/**
 * Role assignments against a real CockroachDB: an assignment shows in the person's roles and in
 * what they may do on their next decision, a repeat changes nothing, removal takes it back, and
 * a role the organization does not have is refused.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MatrixRoleAssignments.class, StandardRoleSeeder.class, AccessFactsQueries.class,
        DatabaseAccessFactsSource.class, CachedAccessFactsSource.class, MatrixAccessService.class,
        MatrixRoleAssignmentsIT.Caching.class})
class MatrixRoleAssignmentsIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class Caching {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    private static final AccessPermission RECEIVE_GOODS = AccessPermission.of("goods-receipts:create");

    @Autowired
    private AccessRoleAssignments assignments;

    @Autowired
    private AccessService access;

    @Autowired
    private StandardRoleSeeder seeder;

    @Autowired
    private TestEntityManager em;

    private Organization org;
    private Employee person;
    private AccessSubject subject;

    @BeforeEach
    void seededOrganization() {
        org = persistOrganization("Assign Roles Org");
        person = persistEmployee(org, "assignee");
        seeder.seed(org.getId());
        em.flush();
        subject = new AccessSubject(org.getId(), person.getId(), null);
    }

    @Test
    void anAssignmentAppliesOnTheNextDecision() {
        assertThat(access.check(subject, RECEIVE_GOODS)).isFalse();

        assignments.assign(org.getId(), person.getId(), "store-keeper");
        em.flush();

        assertThat(assignments.rolesOf(org.getId(), person.getId())).containsExactly("store-keeper");
        assertThat(access.check(subject, RECEIVE_GOODS)).isTrue();
    }

    @Test
    void assigningTwiceKeepsOneAssignmentAndRemovingTakesItBack() {
        assignments.assign(org.getId(), person.getId(), "store-keeper");
        assignments.assign(org.getId(), person.getId(), "store-keeper");
        em.flush();
        assertThat(assignments.rolesOf(org.getId(), person.getId())).containsExactly("store-keeper");
        assertThat(access.check(subject, RECEIVE_GOODS)).isTrue();

        assignments.remove(org.getId(), person.getId(), "store-keeper");
        em.flush();

        assertThat(assignments.rolesOf(org.getId(), person.getId())).isEmpty();
        assertThat(access.check(subject, RECEIVE_GOODS)).isFalse();
    }

    @Test
    void aRoleTheOrganizationDoesNotHaveIsRefused() {
        assertThatThrownBy(() -> assignments.assign(org.getId(), person.getId(), "harbour-master"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("harbour-master");
    }

    private Organization persistOrganization(String name) {
        Organization organization = new Organization();
        organization.setOrganizationName(name);
        organization.setOrganizationAddress(name + " address");
        organization.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        organization.setOrganizationPhone("0000000000");
        em.persist(organization);
        return organization;
    }

    private Employee persistEmployee(Organization organization, String name) {
        User user = new User();
        user.setKeycloakId("kc-" + name);
        user.setName(name);
        em.persist(user);
        Employee employee = new Employee();
        employee.setOrganization(organization);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        em.persist(employee);
        return employee;
    }
}
