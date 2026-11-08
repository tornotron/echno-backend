package org.tornotron.echno_backend.modules.access.matrix.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserRoleRequest;

/**
 * Design note 2.6, rule 4, on every write path: the old employee endpoints' path, the assignment
 * service, and each Roles &amp; Permissions write that could change what the caller holds.
 */
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

    @Test
    void nobodyReplacesTheirOwnRolesThroughTheScreen() {
        Employee admin = administrator("self-screen");
        UUID finance = roleId("finance-manager");

        assertThatThrownBy(() -> writer.replaceUserRoles(admin.getId(), List.of(
                new AccessUserRoleRequest(roleId("system-admin"), null), new AccessUserRoleRequest(finance, null))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("their own roles");
        assertThatThrownBy(() -> writer.replaceUserRoles(admin.getId(), List.of()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(assignmentsOf(admin)).containsExactly("system-admin");
        assertThat(logActionsAbout(admin)).containsExactly("role.assigned");
    }

    @Test
    void nobodyChangesTheGrantsOrGrantableRolesOfARoleTheyHold() {
        Employee admin = administrator("self-role");
        UUID lead = writer.createRole("Lead", null, null).id();
        UUID clerk = writer.createRole("Clerk", null, null).id();
        assignments.assignAsSystem(admin.getId(), lead, null);
        flush();

        assertThatThrownBy(() -> writer.replaceRoleGrants(lead,
                List.of(new AccessGrantRequest("finance:read", "ALL"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("own grants");
        assertThatThrownBy(() -> writer.replaceGrantableRoles(lead, List.of(clerk)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("own grants");
        assertThat(grantsOf(lead)).isEmpty();
        assertThat(logActionsAboutRole(lead)).containsExactly("role.created");
    }
}
