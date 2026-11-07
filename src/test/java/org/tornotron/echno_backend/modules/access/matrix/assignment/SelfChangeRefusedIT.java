package org.tornotron.echno_backend.modules.access.matrix.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;

/** Design note 2.6, rule 4, on the old employee endpoints' path as much as the new one. */
class SelfChangeRefusedIT extends RoleAssignmentITSupport {

    @Test
    void nobodyGivesThemselvesARoleThroughTheOldPath() {
        Employee person = employee("self-assign");
        employeeService.assignOrgRoleAsSystem(person.getId(), OrgRole.SYSTEM_ADMIN);
        flush();
        signInAs(person, "system-admin");

        assertThatThrownBy(() -> employeeService.assignOrgRole(person.getId(), OrgRole.HR_ADMIN))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("their own roles");
        assertThat(legacyRowsOf(person)).containsExactly("SYSTEM_ADMIN");
    }

    @Test
    void nobodyTakesARoleFromThemselvesThroughTheOldPath() {
        Employee person = employee("self-remove");
        employeeService.assignOrgRoleAsSystem(person.getId(), OrgRole.SYSTEM_ADMIN);
        Employee other = employee("other-admin");
        employeeService.assignOrgRoleAsSystem(other.getId(), OrgRole.SYSTEM_ADMIN);
        flush();
        signInAs(person, "system-admin");

        assertThatThrownBy(() -> employeeService.removeOrgRole(person.getId(), OrgRole.SYSTEM_ADMIN))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(legacyRowsOf(person)).containsExactly("SYSTEM_ADMIN");
    }

    @Test
    void nobodyGivesThemselvesARoleThroughTheNewPath() {
        Employee person = employee("self-new");
        employeeService.assignOrgRoleAsSystem(person.getId(), OrgRole.SYSTEM_ADMIN);
        flush();
        signInAs(person, "system-admin");
        java.util.UUID role = jdbc.queryForObject(
                "SELECT id FROM access_role WHERE organization_id = ? AND slug = 'finance-manager'",
                java.util.UUID.class, organization.getId());

        assertThatThrownBy(() -> assignments.assign(person.getId(), role, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theFirstAdministratorIsMadeOneByTheSystem() {
        Employee creator = employee("creator");
        signInAs(creator);

        employeeService.assignOrgRoleAsSystem(creator.getId(), OrgRole.SYSTEM_ADMIN);

        assertThat(legacyRowsOf(creator)).containsExactly("SYSTEM_ADMIN");
        assertThat(assignmentsOf(creator)).containsExactly("system-admin");
    }
}
