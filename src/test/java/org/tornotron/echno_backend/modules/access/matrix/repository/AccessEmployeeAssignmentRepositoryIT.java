package org.tornotron.echno_backend.modules.access.matrix.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrant;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * A person's own roles and grants against a real CockroachDB: they load with the role's grants,
 * and a person holds a role or grant once across the organization and once per project, which a
 * null project column alone would not hold.
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

    private AccessFixtures fixtures;

    @BeforeEach
    void fixtures() {
        fixtures = new AccessFixtures(em.getEntityManager());
    }

    @AfterEach
    void disableFilter() {
        em.getEntityManager().unwrap(Session.class).disableFilter("orgFilter");
    }

    private void scopeTo(Organization org) {
        em.getEntityManager().unwrap(Session.class)
                .enableFilter("orgFilter").setParameter("organizationId", org.getId());
    }

    @Test
    void aPersonsRolesLoadWithTheirGrants() {
        Organization org = fixtures.organization("Assign Org A");
        Employee person = fixtures.employee(org, "assign-a");
        AccessRole keeper = fixtures.newRole(org, "store-keeper");
        keeper.grant(AccessPermission.of("inventory:edit"), AccessScope.ASSIGNED_PROJECTS);
        em.persist(keeper);
        fixtures.assign(org, person, keeper, null);
        em.flush();
        em.clear();

        scopeTo(org);

        assertThat(employeeRoles.findByEmployeeWithGrants(person.getId())).singleElement().satisfies(a -> {
            assertThat(a.getRole().getSlug()).isEqualTo("store-keeper");
            assertThat(a.getProjectId()).isNull();
            assertThat(a.getRole().getGrants()).extracting(AccessRoleGrant::getPermissionKey)
                    .containsExactly("inventory:edit");
        });
        assertThat(employeeRoles.findByRole(keeper.getId())).hasSize(1);
    }

    @Test
    void theOrgFilterHidesAnotherOrganizationsAssignments() {
        Organization mine = fixtures.organization("Assign Org B");
        Organization theirs = fixtures.organization("Assign Org C");
        Employee person = fixtures.employee(theirs, "assign-b");
        fixtures.assign(theirs, person, fixtures.role(theirs, "site-engineer"), null);
        fixtures.grant(theirs, person, "finance:read", AccessScope.RELATED, null);
        em.flush();
        em.clear();

        scopeTo(mine);

        assertThat(employeeRoles.findByEmployeeWithGrants(person.getId())).isEmpty();
        assertThat(employeeGrants.findByEmployee(person.getId())).isEmpty();
    }

    @Test
    void aRoleIsHeldAcrossTheOrganizationOnce() {
        Organization org = fixtures.organization("Assign Org D");
        Employee person = fixtures.employee(org, "assign-d");
        AccessRole role = fixtures.role(org, "hr-admin");
        fixtures.assign(org, person, role, null);
        fixtures.assign(org, person, role, null);

        assertThatThrownBy(() -> em.flush()).hasStackTraceContaining("uq_access_employee_role_org_wide");
    }

    @Test
    void aRoleIsHeldOnAProjectOnce() {
        Organization org = fixtures.organization("Assign Org E");
        Employee person = fixtures.employee(org, "assign-e");
        Project tower = fixtures.project(org, "Tower E");
        AccessRole role = fixtures.role(org, "site-engineer");
        fixtures.assign(org, person, role, tower.getId());
        fixtures.assign(org, person, role, tower.getId());

        assertThatThrownBy(() -> em.flush()).hasStackTraceContaining("uq_access_employee_role_project");
    }

    @Test
    void theSameRoleMayBeHeldOnTwoProjectsAndAcrossTheOrganization() {
        Organization org = fixtures.organization("Assign Org F");
        Employee person = fixtures.employee(org, "assign-f");
        AccessRole role = fixtures.role(org, "site-engineer");
        fixtures.assign(org, person, role, fixtures.project(org, "Tower F1").getId());
        fixtures.assign(org, person, role, fixtures.project(org, "Tower F2").getId());
        fixtures.assign(org, person, role, null);

        em.flush();

        assertThat(employeeRoles.findByRole(role.getId())).hasSize(3);
    }

    @Test
    void aDirectGrantIsHeldAcrossTheOrganizationOnce() {
        Organization org = fixtures.organization("Assign Org G");
        Employee person = fixtures.employee(org, "assign-g");
        fixtures.grant(org, person, "finance:read", AccessScope.ALL, null);
        fixtures.grant(org, person, "finance:read", AccessScope.ALL, null);

        assertThatThrownBy(() -> em.flush()).hasStackTraceContaining("uq_access_employee_grant_org_wide");
    }

    @Test
    void aDirectGrantIsHeldOnAProjectOnce() {
        Organization org = fixtures.organization("Assign Org H");
        Employee person = fixtures.employee(org, "assign-h");
        Project tower = fixtures.project(org, "Tower H");
        fixtures.grant(org, person, "finance:read", AccessScope.ALL, tower.getId());
        fixtures.grant(org, person, "finance:read", AccessScope.ALL, tower.getId());

        assertThatThrownBy(() -> em.flush()).hasStackTraceContaining("uq_access_employee_grant_project");
    }

    @Test
    void assignmentsGoWithTheProjectAndWithTheEmployee() {
        Organization org = fixtures.organization("Assign Org I");
        Employee person = fixtures.employee(org, "assign-i");
        Project tower = fixtures.project(org, "Tower I");
        AccessRole role = fixtures.role(org, "site-engineer");
        fixtures.assign(org, person, role, tower.getId());
        fixtures.assign(org, person, role, null);
        fixtures.grant(org, person, "finance:read", AccessScope.ALL, null);
        em.flush();

        delete("project", tower.getId());
        em.clear();
        assertThat(employeeRoles.findByRole(role.getId())).singleElement()
                .satisfies(a -> assertThat(a.getProjectId()).isNull());

        delete("employee", person.getId());
        em.clear();
        assertThat(employeeRoles.findByRole(role.getId())).isEmpty();
        assertThat(employeeGrants.findByEmployee(person.getId())).isEmpty();
    }

    private void delete(String table, Long id) {
        em.getEntityManager().createNativeQuery("DELETE FROM " + table + " WHERE id = ?1")
                .setParameter(1, id)
                .executeUpdate();
    }
}
