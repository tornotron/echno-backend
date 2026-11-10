package org.tornotron.echno_backend.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Every leave handler, on the tenant API and its {@code /web} twin, asks the access matrix for the
 * action it performs, and says whose leave it is about where the old guard did: the employee the
 * call names ({@code @leaveAccess.allowsFor}), the caller alone ({@code isSelfInCurrentTenant}), or
 * everyone's ({@code @leaveAccess.acrossOrganization}). Leave policies are an area of their own. The
 * notification inbox carries more than leave, so it asks for membership alone. A new handler fails
 * here until it is given its row.
 */
class LeaveGuardsTest {

    static final String MEMBER = "@access.isMember()";
    static final String BALANCES = can("read") + " and @leaveSecurity.canViewEmployeeBalances(#employeeId)";
    static final String IN_THIS_ORGANIZATION = "@orgSecurity.isCurrentTenant(#organizationId) and ";
    static final String DUPLICATE = policies("maintain")
            + " and @access.canIn(#targetOrganizationId, 'leave-policies', 'maintain')";

    /** Keyed by controller and handler, as {@link #key} names them; valued by the whole guard. */
    static final Map<String, String> GUARD_BY_HANDLER = Map.ofEntries(
            Map.entry("LeaveApprovalController#approve", can("approve")),
            Map.entry("LeaveApprovalController#reject", can("approve")),
            Map.entry("LeaveApprovalController#delegate", can("approve")),
            Map.entry("LeaveApprovalController#getApprovalHistory", can("read")),
            Map.entry("LeaveApprovalController#getApprovalChain", can("read")),
            Map.entry("LeaveApprovalController#canApprove", can("read")),
            Map.entry("LeaveApprovalControllerWeb#approve", can("approve")),
            Map.entry("LeaveApprovalControllerWeb#reject", can("approve")),
            Map.entry("LeaveApprovalControllerWeb#delegate", can("approve")),
            Map.entry("LeaveApprovalControllerWeb#getApprovalHistory", can("read")),
            Map.entry("LeaveApprovalControllerWeb#getApprovalChain", can("read")),
            Map.entry("LeaveApprovalControllerWeb#canApprove", can("read")),
            Map.entry("LeaveBalanceController#getEmployeeBalances", BALANCES),
            Map.entry("LeaveBalanceController#getSpecificBalance", BALANCES),
            Map.entry("LeaveBalanceController#getBalanceSummary", BALANCES),
            Map.entry("LeaveBalanceController#recalculateBalances", can("adjust-balances")),
            Map.entry("LeaveBalanceController#adjustBalance", can("adjust-balances")),
            Map.entry("LeaveBalanceController#getTransactionHistory", forEmployee("read")),
            Map.entry("LeaveBalanceController#getTransactionsByBalance", acrossOrganization("read")),
            Map.entry("LeaveBalanceControllerWeb#getEmployeeBalances", BALANCES),
            Map.entry("LeaveBalanceControllerWeb#getSpecificBalance", BALANCES),
            Map.entry("LeaveBalanceControllerWeb#getBalanceSummary", BALANCES),
            Map.entry("LeaveBalanceControllerWeb#recalculateBalances", can("adjust-balances")),
            Map.entry("LeaveBalanceControllerWeb#adjustBalance", can("adjust-balances")),
            Map.entry("LeaveBalanceControllerWeb#getTransactionHistory", forEmployee("read")),
            Map.entry("LeaveBalanceControllerWeb#getTransactionsByBalance", acrossOrganization("read")),
            Map.entry("LeaveCalendarController#getOrganizationCalendar", acrossOrganization("read")),
            Map.entry("LeaveCalendarController#getDepartmentCalendar", acrossOrganization("read")),
            Map.entry("LeaveCalendarController#getEmployeeCalendar", forEmployee("read")),
            Map.entry("LeaveCalendarController#getTeamCalendar", can("read")),
            Map.entry("LeaveCalendarController#getCalendarGroupedByDate", acrossOrganization("read")),
            Map.entry("LeaveCalendarController#getEmployeesOnLeaveCount", acrossOrganization("read")),
            Map.entry("LeaveCalendarControllerWeb#getOrganizationCalendar", acrossOrganization("read")),
            Map.entry("LeaveCalendarControllerWeb#getDepartmentCalendar", acrossOrganization("read")),
            Map.entry("LeaveCalendarControllerWeb#getEmployeeCalendar", forEmployee("read")),
            Map.entry("LeaveCalendarControllerWeb#getTeamCalendar", can("read")),
            Map.entry("LeaveCalendarControllerWeb#getCalendarGroupedByDate", acrossOrganization("read")),
            Map.entry("LeaveCalendarControllerWeb#getEmployeesOnLeaveCount", acrossOrganization("read")),
            Map.entry("LeavePolicyController#createPolicy", policies("create")),
            Map.entry("LeavePolicyController#getPolicy", policies("read")),
            Map.entry("LeavePolicyController#getAllPolicies", policies("read")),
            Map.entry("LeavePolicyController#getPoliciesByOrganization", IN_THIS_ORGANIZATION + policies("read")),
            Map.entry("LeavePolicyController#getApplicablePoliciesForEmployee", forEmployee("read")),
            Map.entry("LeavePolicyController#updatePolicy", policies("maintain")),
            Map.entry("LeavePolicyController#deactivatePolicy", policies("maintain")),
            Map.entry("LeavePolicyController#activatePolicy", policies("maintain")),
            Map.entry("LeavePolicyController#duplicatePolicy", DUPLICATE),
            Map.entry("LeavePolicyControllerWeb#createPolicy", policies("create")),
            Map.entry("LeavePolicyControllerWeb#getPolicy", policies("read")),
            Map.entry("LeavePolicyControllerWeb#getAllPolicies", policies("read")),
            Map.entry("LeavePolicyControllerWeb#getApplicablePoliciesForEmployee", forEmployee("read")),
            Map.entry("LeavePolicyControllerWeb#updatePolicy", policies("maintain")),
            Map.entry("LeavePolicyControllerWeb#deactivatePolicy", policies("maintain")),
            Map.entry("LeavePolicyControllerWeb#activatePolicy", policies("maintain")),
            Map.entry("LeavePolicyControllerWeb#duplicatePolicy", DUPLICATE),
            Map.entry("LeaveRequestController#createRequest", forEmployee("create")),
            Map.entry("LeaveRequestController#getRequest", can("read")),
            Map.entry("LeaveRequestController#getEmployeeRequests", selfOnly("read")),
            Map.entry("LeaveRequestController#getEmployeeRequestsByStatus", acrossOrganization("read")),
            Map.entry("LeaveRequestController#getOrganizationRequests", IN_THIS_ORGANIZATION + acrossOrganization("read")),
            Map.entry("LeaveRequestController#getPendingApprovals", can("read")),
            Map.entry("LeaveRequestController#getPendingApprovalCount", can("read")),
            Map.entry("LeaveRequestController#updateRequest", can("edit")),
            Map.entry("LeaveRequestController#submitRequest", can("edit")),
            Map.entry("LeaveRequestController#cancelRequest", can("edit")),
            Map.entry("LeaveRequestController#withdrawRequest", can("edit")),
            Map.entry("LeaveRequestController#getConflictingDates", selfOnly("read")),
            Map.entry("LeaveRequestController#calculateDays", can("read")),
            Map.entry("LeaveRequestControllerWeb#createRequest", forEmployee("create")),
            Map.entry("LeaveRequestControllerWeb#getRequest", can("read")),
            Map.entry("LeaveRequestControllerWeb#getEmployeeRequests", selfOnly("read")),
            Map.entry("LeaveRequestControllerWeb#getEmployeeRequestsByStatus", acrossOrganization("read")),
            Map.entry("LeaveRequestControllerWeb#getOrganizationRequests", acrossOrganization("read")),
            Map.entry("LeaveRequestControllerWeb#getRequestsByApprover", can("read")),
            Map.entry("LeaveRequestControllerWeb#getPendingApprovals", can("read")),
            Map.entry("LeaveRequestControllerWeb#getPendingApprovalCount", can("read")),
            Map.entry("LeaveRequestControllerWeb#updateRequest", forEmployee("edit")),
            Map.entry("LeaveRequestControllerWeb#submitRequest", forEmployee("edit")),
            Map.entry("LeaveRequestControllerWeb#cancelRequest", forEmployee("edit")),
            Map.entry("LeaveRequestControllerWeb#withdrawRequest", forEmployee("edit")),
            Map.entry("LeaveRequestControllerWeb#getConflictingDates", selfOnly("read")),
            Map.entry("LeaveRequestControllerWeb#calculateDays", can("read")),
            Map.entry("NotificationController#getNotifications", MEMBER),
            Map.entry("NotificationController#getUnreadNotifications", MEMBER),
            Map.entry("NotificationController#getUnreadCount", MEMBER),
            Map.entry("NotificationController#markAsRead", MEMBER),
            Map.entry("NotificationController#markAllAsRead", MEMBER),
            Map.entry("NotificationControllerWeb#getNotifications", MEMBER),
            Map.entry("NotificationControllerWeb#getUnreadNotifications", MEMBER),
            Map.entry("NotificationControllerWeb#getUnreadCount", MEMBER),
            Map.entry("NotificationControllerWeb#markAsRead", MEMBER),
            Map.entry("NotificationControllerWeb#markAllAsRead", MEMBER));

    @Test
    void everyHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(LeaveGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards).containsExactlyInAnyOrderEntriesOf(GUARD_BY_HANDLER);
    }

    static String can(String action) {
        return "@access.can('leave', '" + action + "')";
    }

    static String forEmployee(String action) {
        return can(action) + " and @leaveAccess.allowsFor('" + action + "', #employeeId)";
    }

    static String selfOnly(String action) {
        return can(action) + " and @orgSecurity.isSelfInCurrentTenant(#employeeId)";
    }

    static String acrossOrganization(String action) {
        return can(action) + " and @leaveAccess.acrossOrganization('" + action + "')";
    }

    static String policies(String action) {
        return "@access.can('leave-policies', '" + action + "')";
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(LeaveApprovalController.class, LeaveApprovalControllerWeb.class,
                        LeaveBalanceController.class, LeaveBalanceControllerWeb.class,
                        LeaveCalendarController.class, LeaveCalendarControllerWeb.class,
                        LeavePolicyController.class, LeavePolicyControllerWeb.class,
                        LeaveRequestController.class, LeaveRequestControllerWeb.class,
                        NotificationController.class, NotificationControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
