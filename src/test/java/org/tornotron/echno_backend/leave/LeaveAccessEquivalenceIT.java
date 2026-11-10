package org.tornotron.echno_backend.leave;

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
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.AccessGuardExpressions;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;

/**
 * Moving leave, on the tenant API and its {@code /web} twin, onto the access matrix changes nobody's
 * access (design note 2.5).
 *
 * <p>Before the move, five shapes of check decided it: any member; the employee the call names, or
 * the system admin or HR admin on anyone's (a balance read also let in the employee's management
 * line, which is unchanged and not exercised here); the caller alone; the system admin or HR admin;
 * and the system admin alone, for creating a policy. After it, the guard asks the area and
 * {@link LeaveAccess} asks whose leave it is. Each handler is checked for a caller acting on their
 * own leave and on a colleague's, through the real bean and the seeded starting roles.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 */
@Import(LeaveAccess.class)
class LeaveAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> ADMINS = Set.of("system-admin", "hr-admin");

    /** Each handler's old rule: whether any member passed, whether the employee passed on their own leave, and which roles passed on anyone's. */
    private record Old(boolean anyMember, boolean self, Set<String> roles) {}

    private static final Old ANY_MEMBER = new Old(true, true, Set.of());
    private static final Old SELF_OR_ADMIN = new Old(false, true, ADMINS);
    private static final Old SELF_ONLY = new Old(false, true, Set.of());
    private static final Old ADMIN = new Old(false, false, ADMINS);
    private static final Old SYSTEM_ADMIN = new Old(false, false, Set.of("system-admin"));

    private static final Map<String, Old> OLD_GUARDS = Map.ofEntries(
            Map.entry("LeaveApprovalController#approve", ANY_MEMBER),
            Map.entry("LeaveApprovalController#reject", ANY_MEMBER),
            Map.entry("LeaveApprovalController#delegate", ANY_MEMBER),
            Map.entry("LeaveApprovalController#getApprovalHistory", ANY_MEMBER),
            Map.entry("LeaveApprovalController#getApprovalChain", ANY_MEMBER),
            Map.entry("LeaveApprovalController#canApprove", ANY_MEMBER),
            Map.entry("LeaveApprovalControllerWeb#approve", ANY_MEMBER),
            Map.entry("LeaveApprovalControllerWeb#reject", ANY_MEMBER),
            Map.entry("LeaveApprovalControllerWeb#delegate", ANY_MEMBER),
            Map.entry("LeaveApprovalControllerWeb#getApprovalHistory", ANY_MEMBER),
            Map.entry("LeaveApprovalControllerWeb#getApprovalChain", ANY_MEMBER),
            Map.entry("LeaveApprovalControllerWeb#canApprove", ANY_MEMBER),
            Map.entry("LeaveBalanceController#getEmployeeBalances", SELF_OR_ADMIN),
            Map.entry("LeaveBalanceController#getSpecificBalance", SELF_OR_ADMIN),
            Map.entry("LeaveBalanceController#getBalanceSummary", SELF_OR_ADMIN),
            Map.entry("LeaveBalanceController#recalculateBalances", ADMIN),
            Map.entry("LeaveBalanceController#adjustBalance", ADMIN),
            Map.entry("LeaveBalanceController#getTransactionHistory", SELF_OR_ADMIN),
            Map.entry("LeaveBalanceController#getTransactionsByBalance", ADMIN),
            Map.entry("LeaveBalanceControllerWeb#getEmployeeBalances", SELF_OR_ADMIN),
            Map.entry("LeaveBalanceControllerWeb#getSpecificBalance", SELF_OR_ADMIN),
            Map.entry("LeaveBalanceControllerWeb#getBalanceSummary", SELF_OR_ADMIN),
            Map.entry("LeaveBalanceControllerWeb#recalculateBalances", ADMIN),
            Map.entry("LeaveBalanceControllerWeb#adjustBalance", ADMIN),
            Map.entry("LeaveBalanceControllerWeb#getTransactionHistory", SELF_OR_ADMIN),
            Map.entry("LeaveBalanceControllerWeb#getTransactionsByBalance", ADMIN),
            Map.entry("LeaveCalendarController#getOrganizationCalendar", ADMIN),
            Map.entry("LeaveCalendarController#getDepartmentCalendar", ADMIN),
            Map.entry("LeaveCalendarController#getEmployeeCalendar", SELF_OR_ADMIN),
            Map.entry("LeaveCalendarController#getTeamCalendar", ANY_MEMBER),
            Map.entry("LeaveCalendarController#getCalendarGroupedByDate", ADMIN),
            Map.entry("LeaveCalendarController#getEmployeesOnLeaveCount", ADMIN),
            Map.entry("LeaveCalendarControllerWeb#getOrganizationCalendar", ADMIN),
            Map.entry("LeaveCalendarControllerWeb#getDepartmentCalendar", ADMIN),
            Map.entry("LeaveCalendarControllerWeb#getEmployeeCalendar", SELF_OR_ADMIN),
            Map.entry("LeaveCalendarControllerWeb#getTeamCalendar", ANY_MEMBER),
            Map.entry("LeaveCalendarControllerWeb#getCalendarGroupedByDate", ADMIN),
            Map.entry("LeaveCalendarControllerWeb#getEmployeesOnLeaveCount", ADMIN),
            Map.entry("LeavePolicyController#createPolicy", SYSTEM_ADMIN),
            Map.entry("LeavePolicyController#getPolicy", ADMIN),
            Map.entry("LeavePolicyController#getAllPolicies", ADMIN),
            Map.entry("LeavePolicyController#getPoliciesByOrganization", ADMIN),
            Map.entry("LeavePolicyController#getApplicablePoliciesForEmployee", SELF_OR_ADMIN),
            Map.entry("LeavePolicyController#updatePolicy", ADMIN),
            Map.entry("LeavePolicyController#deactivatePolicy", ADMIN),
            Map.entry("LeavePolicyController#activatePolicy", ADMIN),
            Map.entry("LeavePolicyController#duplicatePolicy", ADMIN),
            Map.entry("LeavePolicyControllerWeb#createPolicy", SYSTEM_ADMIN),
            Map.entry("LeavePolicyControllerWeb#getPolicy", ADMIN),
            Map.entry("LeavePolicyControllerWeb#getAllPolicies", ADMIN),
            Map.entry("LeavePolicyControllerWeb#getApplicablePoliciesForEmployee", SELF_OR_ADMIN),
            Map.entry("LeavePolicyControllerWeb#updatePolicy", ADMIN),
            Map.entry("LeavePolicyControllerWeb#deactivatePolicy", ADMIN),
            Map.entry("LeavePolicyControllerWeb#activatePolicy", ADMIN),
            Map.entry("LeavePolicyControllerWeb#duplicatePolicy", ADMIN),
            Map.entry("LeaveRequestController#createRequest", SELF_OR_ADMIN),
            Map.entry("LeaveRequestController#getRequest", ANY_MEMBER),
            Map.entry("LeaveRequestController#getEmployeeRequests", SELF_ONLY),
            Map.entry("LeaveRequestController#getEmployeeRequestsByStatus", ADMIN),
            Map.entry("LeaveRequestController#getOrganizationRequests", ADMIN),
            Map.entry("LeaveRequestController#getPendingApprovals", ANY_MEMBER),
            Map.entry("LeaveRequestController#getPendingApprovalCount", ANY_MEMBER),
            Map.entry("LeaveRequestController#updateRequest", ANY_MEMBER),
            Map.entry("LeaveRequestController#submitRequest", ANY_MEMBER),
            Map.entry("LeaveRequestController#cancelRequest", ANY_MEMBER),
            Map.entry("LeaveRequestController#withdrawRequest", ANY_MEMBER),
            Map.entry("LeaveRequestController#getConflictingDates", SELF_ONLY),
            Map.entry("LeaveRequestController#calculateDays", ANY_MEMBER),
            Map.entry("LeaveRequestControllerWeb#createRequest", SELF_OR_ADMIN),
            Map.entry("LeaveRequestControllerWeb#getRequest", ANY_MEMBER),
            Map.entry("LeaveRequestControllerWeb#getEmployeeRequests", SELF_ONLY),
            Map.entry("LeaveRequestControllerWeb#getEmployeeRequestsByStatus", ADMIN),
            Map.entry("LeaveRequestControllerWeb#getOrganizationRequests", ADMIN),
            Map.entry("LeaveRequestControllerWeb#getRequestsByApprover", ANY_MEMBER),
            Map.entry("LeaveRequestControllerWeb#getPendingApprovals", ANY_MEMBER),
            Map.entry("LeaveRequestControllerWeb#getPendingApprovalCount", ANY_MEMBER),
            Map.entry("LeaveRequestControllerWeb#updateRequest", SELF_OR_ADMIN),
            Map.entry("LeaveRequestControllerWeb#submitRequest", SELF_OR_ADMIN),
            Map.entry("LeaveRequestControllerWeb#cancelRequest", SELF_OR_ADMIN),
            Map.entry("LeaveRequestControllerWeb#withdrawRequest", SELF_OR_ADMIN),
            Map.entry("LeaveRequestControllerWeb#getConflictingDates", SELF_ONLY),
            Map.entry("LeaveRequestControllerWeb#calculateDays", ANY_MEMBER),
            Map.entry("NotificationController#getNotifications", ANY_MEMBER),
            Map.entry("NotificationController#getUnreadNotifications", ANY_MEMBER),
            Map.entry("NotificationController#getUnreadCount", ANY_MEMBER),
            Map.entry("NotificationController#markAsRead", ANY_MEMBER),
            Map.entry("NotificationController#markAllAsRead", ANY_MEMBER),
            Map.entry("NotificationControllerWeb#getNotifications", ANY_MEMBER),
            Map.entry("NotificationControllerWeb#getUnreadNotifications", ANY_MEMBER),
            Map.entry("NotificationControllerWeb#getUnreadCount", ANY_MEMBER),
            Map.entry("NotificationControllerWeb#markAsRead", ANY_MEMBER),
            Map.entry("NotificationControllerWeb#markAllAsRead", ANY_MEMBER));

    @Autowired
    private AccessService access;

    @Autowired
    private LeaveAccess leave;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(LeaveGuardsTest.handlers()).extracting(LeaveGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("leave-eq-" + role.getGroupName());
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
        Employee person = employee("leave-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("leave-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        Employee colleague = employee("leave-eq-colleague");
        flush();
        signInAs(person);
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : LeaveGuardsTest.handlers()) {
            String key = LeaveGuardsTest.key(handler);
            Old old = OLD_GUARDS.get(key);
            String expression = handler.getAnnotation(PreAuthorize.class).value();
            if (expression.equals("@access.isMember()")) {
                // The inbox carries more than leave and asks for membership alone, as before.
                assertThat(old).as(key).isEqualTo(ANY_MEMBER);
                continue;
            }
            AccessPermission permission = permissionOf(expression, key);
            boolean guard = access.check(subject, permission);
            boolean held = old.roles().stream().anyMatch(oldRoles::contains);

            boolean beforeOnOwn = old.anyMember() || old.self() || held;
            boolean beforeOnColleague = old.anyMember() || held;
            boolean afterOnOwn = guard && beyondTheGuard(expression, permission, person, person);
            boolean afterOnColleague = guard && beyondTheGuard(expression, permission, person, colleague);

            assertThat(afterOnOwn).as("%s on their own, for %s", key, oldRoles).isEqualTo(beforeOnOwn);
            assertThat(afterOnColleague).as("%s on a colleague, for %s", key, oldRoles)
                    .isEqualTo(beforeOnColleague);
        }
    }

    /** What the guard asks after the area, for a call about {@code concerned}'s leave. */
    private boolean beyondTheGuard(String expression, AccessPermission permission, Employee caller,
                                   Employee concerned) {
        if (expression.contains("@leaveAccess.allowsFor") || expression.contains("canViewEmployeeBalances")) {
            return leave.allowsFor(permission.action(), concerned.getId());
        }
        if (expression.contains("isSelfInCurrentTenant")) {
            return caller.getId().equals(concerned.getId());
        }
        if (expression.contains("@leaveAccess.acrossOrganization")) {
            return leave.acrossOrganization(permission.action());
        }
        return true;
    }

    private static AccessPermission permissionOf(String expression, String key) {
        Set<AccessPermission> permissions = AccessGuardExpressions.parse(expression).permissions();
        assertThat(permissions).as(key).hasSize(1);
        return permissions.iterator().next();
    }
}
