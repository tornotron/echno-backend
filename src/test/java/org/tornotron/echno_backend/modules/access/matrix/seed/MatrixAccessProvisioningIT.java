package org.tornotron.echno_backend.modules.access.matrix.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
import org.tornotron.echno_backend.modules.access.api.AccessProvisioning;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessFactsQueries;
import org.tornotron.echno_backend.modules.access.matrix.service.CachedAccessFactsSource;
import org.tornotron.echno_backend.modules.access.matrix.service.DatabaseAccessFactsSource;
import org.tornotron.echno_backend.modules.access.matrix.service.MatrixAccessService;
import org.tornotron.echno_backend.modules.access.matrix.service.MatrixRoleAssignments;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

/**
 * Provisioning a new organization end to end against a real CockroachDB: after one call the owner
 * holds everything, an ordinary member holds the baseline, and a second call changes nothing.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StandardRoleSeeder.class, MatrixAccessProvisioning.class, AccessFactsQueries.class,
        DatabaseAccessFactsSource.class, CachedAccessFactsSource.class, MatrixAccessService.class,
        MatrixRoleAssignments.class, MatrixAccessProvisioningIT.Caching.class})
class MatrixAccessProvisioningIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class Caching {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    @Autowired
    private AccessProvisioning provisioning;

    @Autowired
    private AccessService access;

    @Autowired
    private TestEntityManager em;

    @Test
    void theOwnerAdministersTheNewOrganizationAndEveryoneElseHoldsTheBaseline() {
        Organization org = persistOrganization("Provision Org A");
        Employee owner = persistEmployee(org, "owner-a");
        Employee member = persistEmployee(org, "member-a");
        em.flush();

        provisioning.provisionOrganization(org.getId(), owner.getId());
        em.flush();
        em.clear();

        AccessSubject asOwner = new AccessSubject(org.getId(), owner.getId(), null);
        AccessSubject asMember = new AccessSubject(org.getId(), member.getId(), null);
        assertThat(access.check(asOwner, AccessPermission.of("access:edit"))).isTrue();
        assertThat(access.check(asOwner, AccessPermission.of("finance:delete"))).isTrue();
        assertThat(access.check(asMember, AccessPermission.of("projects:view"))).isTrue();
        assertThat(access.check(asMember, AccessPermission.of("leave:create"))).isTrue();
        assertThat(access.check(asMember, AccessPermission.of("finance:view"))).isFalse();
        assertThat(access.check(asMember, AccessPermission.of("access:edit"))).isFalse();
    }

    @Test
    void provisioningTwiceChangesNothing() {
        Organization org = persistOrganization("Provision Org B");
        Employee owner = persistEmployee(org, "owner-b");
        em.flush();

        provisioning.provisionOrganization(org.getId(), owner.getId());
        provisioning.provisionOrganization(org.getId(), owner.getId());
        em.flush();

        assertThat(em.getEntityManager()
                .createQuery("SELECT COUNT(a) FROM AccessEmployeeRole a WHERE a.employeeId = :e", Long.class)
                .setParameter("e", owner.getId()).getSingleResult()).isEqualTo(1L);
        assertThat(em.getEntityManager()
                .createQuery("SELECT COUNT(r) FROM AccessRole r WHERE r.organization.id = :o", Long.class)
                .setParameter("o", org.getId()).getSingleResult()).isEqualTo((long) StandardRoles.all().size());
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
        user.setKeycloakId("kc-" + name);
        user.setName(name);
        em.persist(user);
        Employee employee = new Employee();
        employee.setOrganization(org);
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
