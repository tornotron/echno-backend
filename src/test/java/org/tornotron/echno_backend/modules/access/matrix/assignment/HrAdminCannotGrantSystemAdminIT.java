package org.tornotron.echno_backend.modules.access.matrix.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;

/** Design note 2.6, rule 1: audit G-01, where an HR administrator could make anyone an administrator. */
class HrAdminCannotGrantSystemAdminIT extends RoleAssignmentITSupport {

    private Employee hrAdmin() {
        Employee hr = employee("hr");
        employeeService.assignOrgRoleAsSystem(hr.getId(), OrgRole.HR_ADMIN);
        flush();
        signInAs(hr, "hr-admin");
        return hr;
    }

    @Test
    void anHrAdminCannotMakeSomeoneAnAdministrator() {
        hrAdmin();
        Employee target = employee("target");

        assertThatThrownBy(() -> employeeService.assignOrgRole(target.getId(), OrgRole.SYSTEM_ADMIN))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(legacyRowsOf(target)).isEmpty();
        assertThat(assignmentsOf(target)).isEmpty();
    }

    @Test
    void anHrAdminCannotTakeTheRoleFromAnAdministrator() {
        Employee admin = employee("admin");
        employeeService.assignOrgRoleAsSystem(admin.getId(), OrgRole.SYSTEM_ADMIN);
        Employee second = employee("second");
        employeeService.assignOrgRoleAsSystem(second.getId(), OrgRole.SYSTEM_ADMIN);
        flush();
        hrAdmin();

        assertThatThrownBy(() -> employeeService.removeOrgRole(second.getId(), OrgRole.SYSTEM_ADMIN))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anHrAdminStillGivesAnOrdinaryRole() {
        hrAdmin();
        Employee target = employee("target-store");

        employeeService.assignOrgRole(target.getId(), OrgRole.STORE_KEEPER);

        assertThat(legacyRowsOf(target)).containsExactly("STORE_KEEPER");
    }

    @Test
    void aRoleWithNoGrantableListAssignsNothing() {
        Employee manager = employee("pm");
        employeeService.assignOrgRoleAsSystem(manager.getId(), OrgRole.PROJECT_MANAGER);
        flush();
        signInAs(manager, "project-manager");
        Employee target = employee("target-pm");

        assertThatThrownBy(() -> employeeService.assignOrgRole(target.getId(), OrgRole.STORE_KEEPER))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aBootstrapAdministratorWithNoEmployeeKeepsAssigning() {
        signInAsSomeoneWithNoEmployeeHolding("system-admin");
        Employee target = employee("target-bootstrap");

        employeeService.assignOrgRole(target.getId(), OrgRole.SYSTEM_ADMIN);

        assertThat(legacyRowsOf(target)).containsExactly("SYSTEM_ADMIN");
    }
}
