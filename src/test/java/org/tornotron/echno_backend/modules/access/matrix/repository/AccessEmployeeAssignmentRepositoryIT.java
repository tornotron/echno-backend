package org.tornotron.echno_backend.modules.access.matrix.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeGrant;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrant;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

/**
 * A person's own roles and grants against a real CockroachDB: they load with the role's grants,
 * stay inside the organization, and a role is assigned to a person once.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccessEmployeeAssignmentRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private AccessEmployeeRoleRepository employeeRoles;

    @Autowired
    private AccessEmployeeGrantRepository employeeGrants;

    @Autowired
    private TestEntityManager em;

    @AfterEach
    void disableFilter() {
        em.getEntityManager().unwrap(Session.class).disableFilter("orgFilter");
    }

    @Test
    void aPersonsRolesLoadWithTheirGrants() {
        Organization org = persistOrganization("Assign Org A");
        Employee person = persistEmployee(org, "assign-a");
        AccessRole keeper = role(org, "store-keeper");
        keeper.grant(AccessPermission.of("inventory:edit"), AccessScope.ASSIGNED_PROJECTS, false);
        em.persist(keeper);
        em.persist(assignment(org, person, keeper));
        em.flush();
        em.clear();

        scopeTo(org);

        assertThat(employeeRoles.findByEmployeeWithGrants(person.getId())).singleElement().satisfies(a -> {
            assertThat(a.getRole().getSlug()).isEqualTo("store-keeper");
            assertThat(a.getRole().getGrants()).extracting(AccessRoleGrant::getPermissionKey)
                    .containsExactly("inventory:edit");
        });
        assertThat(employeeRoles.findByRole(keeper.getId())).hasSize(1);
    }

    @Test
    void directGrantsStayInsideTheOrganization() {
        Organization mine = persistOrganization("Assign Org B");
        Organization theirs = persistOrganization("Assign Org C");
        Employee person = persistEmployee(mine, "assign-b");
        em.persist(directGrant(mine, person, "finance:view", AccessScope.RELATED));
        em.persist(directGrant(theirs, person, "finance:edit", AccessScope.ALL));
        em.flush();
        em.clear();

        scopeTo(mine);

        assertThat(employeeGrants.findByEmployee(person.getId())).singleElement().satisfies(g -> {
            assertThat(g.getPermissionKey()).isEqualTo("finance:view");
            assertThat(g.getScope()).isEqualTo(AccessScope.RELATED);
        });
    }

    @Test
    void aRoleIsAssignedToAPersonOnce() {
        Organization org = persistOrganization("Assign Org D");
        Employee person = persistEmployee(org, "assign-d");
        AccessRole role = role(org, "hr-admin");
        em.persist(role);
        em.persist(assignment(org, person, role));
        em.persist(assignment(org, person, role));

        assertThatThrownBy(() -> em.flush()).isInstanceOf(Exception.class);
    }

    private AccessRole role(Organization org, String slug) {
        AccessRole role = new AccessRole();
        role.setOrganization(org);
        role.setSlug(slug);
        role.setName(slug);
        return role;
    }

    private AccessEmployeeRole assignment(Organization org, Employee person, AccessRole role) {
        AccessEmployeeRole assignment = new AccessEmployeeRole();
        assignment.setOrganization(org);
        assignment.setEmployeeId(person.getId());
        assignment.setRole(role);
        return assignment;
    }

    private AccessEmployeeGrant directGrant(Organization org, Employee person, String key, AccessScope scope) {
        AccessEmployeeGrant grant = new AccessEmployeeGrant();
        grant.setOrganization(org);
        grant.setEmployeeId(person.getId());
        grant.setPermissionKey(key);
        grant.setScope(scope);
        return grant;
    }

    private void scopeTo(Organization org) {
        em.getEntityManager().unwrap(Session.class)
                .enableFilter("orgFilter").setParameter("organizationId", org.getId());
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
