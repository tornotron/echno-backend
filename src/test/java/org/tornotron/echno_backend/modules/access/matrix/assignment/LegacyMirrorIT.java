package org.tornotron.echno_backend.modules.access.matrix.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.project.Project;

/** The screen and the old checks never disagree: per role, the two tables change together. */
class LegacyMirrorIT extends RoleAssignmentITSupport {

    private Employee administrator() {
        Employee admin = employee("mirror-admin");
        employeeService.assignOrgRoleAsSystem(admin.getId(), OrgRole.SYSTEM_ADMIN);
        flush();
        signInAs(admin, "system-admin");
        return admin;
    }

    private UUID role(String slug) {
        return jdbc.queryForObject("SELECT id FROM access_role WHERE organization_id = ? AND slug = ?",
                UUID.class, organization.getId(), slug);
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aRoleChangesInBothTablesAndIsLogged(OrgRole role) {
        administrator();
        Employee target = employee("mirror-target");

        employeeService.assignOrgRole(target.getId(), role);

        assertThat(legacyRowsOf(target)).containsExactly(role.name());
        assertThat(assignmentsOf(target)).hasSize(1);
        assertThat(logActionsAbout(target)).containsExactly("role.assigned");

        employeeService.removeOrgRole(target.getId(), role);

        assertThat(legacyRowsOf(target)).isEmpty();
        assertThat(assignmentsOf(target)).isEmpty();
        assertThat(logActionsAbout(target)).containsExactly("role.assigned", "role.removed");
    }

    @Test
    void giveAndTakeBothReachTheEmployeeTheServiceReturns() {
        administrator();
        Employee target = employee("mirror-dto");

        assertThat(employeeService.assignOrgRole(target.getId(), OrgRole.STORE_KEEPER).getOrgRoles())
                .contains(OrgRole.STORE_KEEPER);
        assertThat(employeeService.removeOrgRole(target.getId(), OrgRole.STORE_KEEPER).getOrgRoles())
                .doesNotContain(OrgRole.STORE_KEEPER);
    }

    @Test
    void doingItAgainChangesNothingAndLogsNothing() {
        administrator();
        Employee target = employee("mirror-repeat");

        employeeService.assignOrgRole(target.getId(), OrgRole.STORE_KEEPER);
        employeeService.assignOrgRole(target.getId(), OrgRole.STORE_KEEPER);

        assertThat(assignmentsOf(target)).hasSize(1);
        assertThat(logActionsAbout(target)).hasSize(1);
    }

    @Test
    void aRoleWithACounterpartRefusesAProject() {
        administrator();
        Employee target = employee("mirror-project");
        Project project = fixtures.project(organization, "Mirror Project " + System.nanoTime());
        flush();

        assertThatThrownBy(() -> assignments.assign(target.getId(), role("store-keeper"), project.getId()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("across the organization");
    }

    @Test
    void aRoleWithNoCounterpartTakesAProjectAndWritesNoOldRow() {
        administrator();
        Employee target = employee("mirror-new-role");
        Project project = fixtures.project(organization, "Mirror New Project " + System.nanoTime());
        flush();

        assignments.assign(target.getId(), role("finance-manager"), project.getId());

        assertThat(assignmentsOf(target)).containsExactly("finance-manager");
        assertThat(legacyRowsOf(target)).isEmpty();

        assignments.remove(target.getId(), role("finance-manager"), project.getId());

        assertThat(assignmentsOf(target)).isEmpty();
    }

    @Test
    void anotherOrganizationsPersonOrProjectIsRefused() {
        administrator();
        Employee target = employee("mirror-cross");
        var other = fixtures.organization("Mirror Other " + System.nanoTime());
        Employee stranger = fixtures.employee(other, "mirror-stranger-" + System.nanoTime());
        Project theirs = fixtures.project(other, "Mirror Their Project " + System.nanoTime());
        flush();

        assertThatThrownBy(() -> assignments.assign(stranger.getId(), role("finance-manager"), null))
                .hasMessageContaining("was not found in this organization");
        assertThatThrownBy(() -> assignments.assign(target.getId(), role("finance-manager"), theirs.getId()))
                .hasMessageContaining("was not found in this organization");
    }
}
