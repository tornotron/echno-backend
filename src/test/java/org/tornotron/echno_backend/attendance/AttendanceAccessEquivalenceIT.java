package org.tornotron.echno_backend.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.TeamAccess;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.AccessGuardExpressions;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;

/**
 * Moving attendance and movement records, on the tenant API and its {@code /web} twin, onto the access
 * matrix changes nobody's access at the guard (design note 2.5).
 *
 * <p>Before the move, three shapes of guard decided it: any member; the employee named, or a holder of
 * a record-management role (system admin, HR admin or project manager) on anyone's; and those roles
 * alone. After it, the guard asks the area and {@link TeamAccess} asks whose records they are. The
 * service checks below the any-member guards are unchanged and not exercised here. Each handler is
 * checked for a caller acting on their own records and on a colleague's, through the real bean and the
 * seeded starting roles.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 */
@Import(TeamAccess.class)
class AttendanceAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> RECORD_MANAGERS = Set.of("system-admin", "hr-admin", "project-manager");

    /** Each handler's old rule: whether any member passed, whether the employee passed on their own records, and which roles passed on anyone's. */
    private record Old(boolean anyMember, boolean self, Set<String> roles) {}

    private static final Old ANY_MEMBER = new Old(true, true, Set.of());
    private static final Old MANAGER = new Old(false, false, RECORD_MANAGERS);
    private static final Old SELF_OR_MANAGER = new Old(false, true, RECORD_MANAGERS);

    private static final Map<String, Old> OLD_GUARDS = Map.ofEntries(
            Map.entry("AttendanceController#checkIn", ANY_MEMBER),
            Map.entry("AttendanceController#recordClockEvent", ANY_MEMBER),
            Map.entry("AttendanceController#getById", ANY_MEMBER),
            Map.entry("AttendanceController#getByEmployee", SELF_OR_MANAGER),
            Map.entry("AttendanceController#getByProject", MANAGER),
            Map.entry("AttendanceController#approve", ANY_MEMBER),
            Map.entry("AttendanceController#getPendingApprovals", ANY_MEMBER),
            Map.entry("AttendanceController#getPendingApprovalCount", ANY_MEMBER),
            Map.entry("AttendanceController#markAbsent", MANAGER),
            Map.entry("AttendanceController#getMonthlySummary", SELF_OR_MANAGER),
            Map.entry("AttendanceController#delete", MANAGER),
            Map.entry("AttendanceControllerWeb#checkIn", ANY_MEMBER),
            Map.entry("AttendanceControllerWeb#recordClockEvent", ANY_MEMBER),
            Map.entry("AttendanceControllerWeb#getById", ANY_MEMBER),
            Map.entry("AttendanceControllerWeb#getByEmployee", SELF_OR_MANAGER),
            Map.entry("AttendanceControllerWeb#getByProject", MANAGER),
            Map.entry("AttendanceControllerWeb#approve", ANY_MEMBER),
            Map.entry("AttendanceControllerWeb#getPendingApprovals", ANY_MEMBER),
            Map.entry("AttendanceControllerWeb#getPendingApprovalCount", ANY_MEMBER),
            Map.entry("AttendanceControllerWeb#markAbsent", MANAGER),
            Map.entry("AttendanceControllerWeb#getMonthlySummary", SELF_OR_MANAGER),
            Map.entry("AttendanceControllerWeb#delete", MANAGER),
            Map.entry("MovementRecordController#create", ANY_MEMBER),
            Map.entry("MovementRecordController#getById", ANY_MEMBER),
            Map.entry("MovementRecordController#getByAttendance", ANY_MEMBER),
            Map.entry("MovementRecordController#verify", MANAGER),
            Map.entry("MovementRecordControllerWeb#create", ANY_MEMBER),
            Map.entry("MovementRecordControllerWeb#getById", ANY_MEMBER),
            Map.entry("MovementRecordControllerWeb#getByAttendance", ANY_MEMBER),
            Map.entry("MovementRecordControllerWeb#verify", MANAGER));

    @Autowired
    private AccessService access;

    @Autowired
    private TeamAccess team;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(AttendanceGuardsTest.handlers()).extracting(AttendanceGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("attendance-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertReachesExactlyWhatTheOldGuardsGave(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartReachesWhatTheBaselineDoes(String slug) {
        Employee person = employee("attendance-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("attendance-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        Employee colleague = employee("attendance-eq-colleague");
        flush();
        signInAs(person);
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : AttendanceGuardsTest.handlers()) {
            String key = AttendanceGuardsTest.key(handler);
            Old old = OLD_GUARDS.get(key);
            String expression = handler.getAnnotation(PreAuthorize.class).value();
            AccessPermission permission = permissionOf(expression, key);
            boolean guard = access.check(subject, permission);
            boolean held = old.roles().stream().anyMatch(oldRoles::contains);

            boolean beforeOnOwn = old.anyMember() || old.self() || held;
            boolean beforeOnColleague = old.anyMember() || held;
            boolean afterOnOwn = guard && beyondTheGuard(expression, permission, person);
            boolean afterOnColleague = guard && beyondTheGuard(expression, permission, colleague);

            assertThat(afterOnOwn).as("%s on their own, for %s", key, oldRoles).isEqualTo(beforeOnOwn);
            assertThat(afterOnColleague).as("%s on a colleague, for %s", key, oldRoles)
                    .isEqualTo(beforeOnColleague);
        }
    }

    /** What the guard asks after the area, for a call about {@code concerned}'s record. */
    private boolean beyondTheGuard(String expression, AccessPermission permission, Employee concerned) {
        if (expression.contains("@teamAccess.allowsFor")) {
            return team.allowsFor(permission.action(), concerned.getId());
        }
        if (expression.contains("@teamAccess.acrossOrganization")) {
            return team.acrossOrganization(permission.action());
        }
        return true;
    }

    private static AccessPermission permissionOf(String expression, String key) {
        Set<AccessPermission> permissions = AccessGuardExpressions.parse(expression).permissions();
        assertThat(permissions).as(key).hasSize(1);
        return permissions.iterator().next();
    }
}
