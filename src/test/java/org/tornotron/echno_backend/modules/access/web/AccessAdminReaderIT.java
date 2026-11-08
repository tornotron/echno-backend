package org.tornotron.echno_backend.modules.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrantable;
import org.tornotron.echno_backend.modules.access.web.dto.AccessAssignmentDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserGrantDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserRoleDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * What the Roles &amp; Permissions screen reads, against CockroachDB: an organization's roles with
 * their grants, assigned people and grantable roles, its people with their roles and grants, and
 * nothing from another organization, even through a row that names one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AccessAdminReader.class)
class AccessAdminReaderIT extends AbstractIntegrationTest {

    @Autowired
    private AccessAdminReader reader;

    @Autowired
    private EntityManager em;

    private AccessFixtures fixtures;
    private Organization org;
    private Organization other;

    @BeforeEach
    void twoOrganizations() {
        fixtures = new AccessFixtures(em);
        org = fixtures.organization("Reader A " + System.nanoTime());
        other = fixtures.organization("Reader B " + System.nanoTime());
    }

    @Test
    void rolesComeWithGrantsAssignmentsAndGrantableRolesForOneOrganizationOnly() {
        Employee keeper = fixtures.employee(org, "reader-keeper-" + System.nanoTime());
        Project tower = fixtures.project(org, "Tower A");
        AccessRole baseline = baseline(org);
        AccessRole storeKeeper = fixtures.newRole(org, "store-keeper");
        storeKeeper.setName("Store Keeper");
        storeKeeper.setLegacyOrgRole("store-keeper");
        storeKeeper.grant(AccessPermission.of("inventory:edit"), AccessScope.ALL);
        em.persist(storeKeeper);
        AccessRole auditor = fixtures.role(org, "auditor");
        auditor.setName("Auditor");
        grantable(org, storeKeeper, auditor);
        fixtures.assign(org, keeper, storeKeeper, null);
        fixtures.assign(org, keeper, auditor, tower.getId());
        AccessRole elsewhere = fixtures.role(other, "auditor");
        elsewhere.setName("Auditor");
        flushAndClear();

        List<AccessRoleDto> roles = reader.roles(org.getId());

        assertThat(roles).extracting(AccessRoleDto::id)
                .containsExactly(baseline.getId(), auditor.getId(), storeKeeper.getId());
        assertThat(roles.get(0).baseline()).isTrue();
        assertThat(roles.get(2)).satisfies(role -> {
            assertThat(role.grants()).containsExactly(new AccessGrantDto("inventory:edit", "ALL"));
            assertThat(role.assignments()).containsExactly(new AccessAssignmentDto(keeper.getId(), null));
            assertThat(role.grantableRoleIds()).containsExactly(auditor.getId());
            assertThat(role.organizationWideOnly()).isTrue();
        });
        assertThat(roles.get(1)).satisfies(role -> {
            assertThat(role.assignments()).containsExactly(new AccessAssignmentDto(keeper.getId(), tower.getId()));
            assertThat(role.organizationWideOnly()).isFalse();
        });
    }

    @Test
    void oneRoleIsReadOnItsOwnAndAnotherOrganizationsReadsAsAbsent() {
        AccessRole mine = fixtures.role(org, "auditor");
        AccessRole theirs = fixtures.role(other, "auditor");
        flushAndClear();

        assertThat(reader.role(org.getId(), mine.getId()).slug()).isEqualTo("auditor");
        assertThatThrownBy(() -> reader.role(org.getId(), theirs.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void peopleComeWithTheirRolesAndGrantsForOneOrganizationOnly() {
        String suffix = "-" + System.nanoTime();
        Employee engineer = fixtures.employee(org, "a-engineer" + suffix);
        Employee leaver = fixtures.employee(org, "b-leaver" + suffix, EmployeeStatus.resigned);
        Employee outsider = fixtures.employee(other, "c-outsider" + suffix);
        Project tower = fixtures.project(org, "Tower A");
        AccessRole site = fixtures.role(org, "site-engineer");
        fixtures.assign(org, engineer, site, tower.getId());
        fixtures.grant(org, engineer, "tasks:read", AccessScope.RELATED, null);
        fixtures.grant(other, outsider, "tasks:read", AccessScope.ALL, null);
        flushAndClear();

        List<AccessUserDto> people = reader.users(org.getId());

        assertThat(people).extracting(AccessUserDto::employeeId).containsExactly(engineer.getId(), leaver.getId());
        assertThat(people.get(0)).satisfies(person -> {
            assertThat(person.active()).isTrue();
            assertThat(person.roles()).containsExactly(new AccessUserRoleDto(site.getId(), tower.getId()));
            assertThat(person.grants()).containsExactly(new AccessUserGrantDto("tasks:read", "RELATED", null));
        });
        assertThat(people.get(1).active()).isFalse();
        assertThat(people.get(1).roles()).isEmpty();
    }

    @Test
    void onePersonIsLoadedOnTheirOwnAndAnotherOrganizationsReadsAsAbsent() {
        String suffix = "-" + System.nanoTime();
        Employee engineer = fixtures.employee(org, "engineer" + suffix);
        Employee colleague = fixtures.employee(org, "colleague" + suffix);
        Employee outsider = fixtures.employee(other, "outsider" + suffix);
        AccessRole site = fixtures.role(org, "site-engineer");
        fixtures.assign(org, engineer, site, null);
        fixtures.assign(org, colleague, site, null);
        flushAndClear();

        AccessUserDto person = reader.user(org.getId(), engineer.getId());

        assertThat(person.employeeId()).isEqualTo(engineer.getId());
        assertThat(person.roles()).containsExactly(new AccessUserRoleDto(site.getId(), null));
        assertThatThrownBy(() -> reader.user(org.getId(), outsider.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anOrganizationWithNothingReadsAsEmpty() {
        flushAndClear();

        assertThat(reader.roles(org.getId())).isEmpty();
        assertThat(reader.users(org.getId())).isEmpty();
    }

    private AccessRole baseline(Organization organization) {
        AccessRole role = fixtures.newRole(organization, "employee");
        role.setName("Employee");
        role.setSystem(true);
        role.setBaseline(true);
        em.persist(role);
        return role;
    }

    private void grantable(Organization organization, AccessRole holder, AccessRole target) {
        AccessRoleGrantable row = new AccessRoleGrantable();
        row.setOrganization(organization);
        row.setRole(holder);
        row.setGrantableRole(target);
        em.persist(row);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
