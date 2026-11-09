package org.tornotron.echno_backend.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;

/**
 * Who reads one regularization request, decided on the matrix through the seeded starting roles:
 * the employee whose attendance it corrects, whoever decided it, and a record-management role, which
 * are the three {@code AttendanceSecurityService.canViewAttendanceRecord} lets read an attendance
 * record. Any other member is refused. {@code RegularisationByIdTwinsTest} carries the answer through
 * both endpoints.
 *
 * <p>The decider here holds no role, so only the decider rule can let them in.
 */
@Import(RegularisationAccess.class)
class RegularisationByIdReadIT extends RoleAssignmentITSupport {

    @Autowired
    private RegularisationAccess regularisations;

    private Employee owner;
    private Employee decider;

    @BeforeEach
    void aDecidedRequest() {
        reconciler.ensureStartingRoles(organization.getId());
        owner = employee("reg-read-owner");
        decider = employee("reg-read-decider");
        flush();
    }

    @Test
    void theEmployeeReadsTheirOwnRequest() {
        signInAs(owner);

        assertThat(regularisations.mayRead(owner.getId(), decider.getId())).isTrue();
    }

    @Test
    void whoeverDecidedItReadsIt() {
        signInAs(decider);

        assertThat(regularisations.mayRead(owner.getId(), decider.getId())).isTrue();
    }

    @Test
    void aRecordManagementRoleReadsIt() {
        for (OrgRole role : new OrgRole[] {OrgRole.SYSTEM_ADMIN, OrgRole.HR_ADMIN, OrgRole.PROJECT_MANAGER}) {
            Employee manager = employee("reg-read-" + role.getGroupName());
            assignments.assignOrgRoleAsSystem(manager.getId(), role);
            flush();
            signInAs(manager);

            assertThat(regularisations.mayRead(owner.getId(), decider.getId())).as(role.getGroupName()).isTrue();
        }
    }

    @Test
    void anUnrelatedMemberIsRefused() {
        Employee bystander = employee("reg-read-bystander");
        flush();
        signInAs(bystander);

        assertThat(regularisations.mayRead(owner.getId(), decider.getId())).isFalse();
    }

    @Test
    void aPendingRequestIsReadByNoDecider() {
        Employee bystander = employee("reg-read-pending");
        flush();
        signInAs(bystander);

        assertThat(regularisations.mayRead(owner.getId(), null)).isFalse();
    }
}
