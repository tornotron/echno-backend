package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An ordinary employee reaches their own notification inbox, and a line manager reaches their own
 * team calendar, on both twins.
 *
 * <p>This is the end-to-end proof, driven through the real security pipeline rather than against
 * the annotation text. Every path here answered 403 to this caller before the change, for one of
 * two reasons. The whole phone surface, eleven endpoints, asked for {@code hasAuthority('leave:read')}
 * or {@code hasAuthority('leave:admin')}, which this realm defines no mechanism to issue, so it
 * refused everybody. The web twins asked for the system-admin or hr-admin role, which an ordinary
 * employee does not hold and which a line manager does not hold either: reporting lines come from
 * {@code Employee.manager} and are independent of the Keycloak-derived role set.
 *
 * <p>That is the gap the leave work left open. {@code LeaveApprovalService} writes a notification
 * on every routing step, so an approver was being told a request had arrived through a channel
 * they could not read.
 *
 * <p>The counterpart matters as much, and the refusal cases carry it. Membership is a coarse gate
 * and deliberately not the whole answer: {@code NotificationService} refuses anybody who is not the
 * recipient stored on the row, and {@code NotificationInboxOwnershipTest} pins that. What this test
 * adds is that the widening reached the workflow and not everybody, and that the organization-wide
 * calendar views kept the administrative roles they already had.
 *
 * <p>All four controllers share one web slice so the suite gains one Spring context rather than
 * four; see {@code EchnoBackendApplicationTests} for why the cached-context budget is watched.
 */
@WebMvcTest({
        NotificationController.class,
        NotificationControllerWeb.class,
        LeaveCalendarController.class,
        LeaveCalendarControllerWeb.class})
@Import(LeaveApprovalWorkflowAuthzTest.TestSecurityConfig.class)
class NotificationAndCalendarReachTest {

    private static final String RANGE = "startDate=2026-09-01&endDate=2026-09-30";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private LeaveCalendarService calendarService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here because
    // .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @BeforeEach
    void stubTheInboxAndTheCalendar() {
        when(notificationService.getMyNotifications(any(Pageable.class))).thenReturn(Page.empty());
        when(notificationService.getMyUnreadNotifications()).thenReturn(List.of());
        when(notificationService.getMyUnreadCount()).thenReturn(0L);
        when(notificationService.markAllAsRead()).thenReturn(0);
        when(calendarService.getCalendarByOrganization(any(), any())).thenReturn(List.of());
        when(calendarService.getCalendarByDepartment(anyString(), any(), any())).thenReturn(List.of());
        when(calendarService.getCalendarByEmployee(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarService.getMyTeamCalendar(any(), any())).thenReturn(List.of());
        when(calendarService.getCalendarGroupedByDate(any(), any())).thenReturn(Map.of());
        when(calendarService.countEmployeesOnLeave(any())).thenReturn(0L);
    }

    /** A site manager: a member of the tenant, holding neither administrative leave role. */
    private void asAnEmployeeHoldingNoAdministrativeRole() {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).thenReturn(false);
        when(orgSecurity.isSelfOrHasAnyOrgRole(anyLong(), anyString(), anyString())).thenReturn(false);
    }

    private void asALeaveAdministrator() {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).thenReturn(true);
        when(orgSecurity.isSelfOrHasAnyOrgRole(anyLong(), anyString(), anyString())).thenReturn(true);
    }

    private void asSomebodyOutsideTheTenant() {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).thenReturn(false);
        when(orgSecurity.isSelfOrHasAnyOrgRole(anyLong(), anyString(), anyString())).thenReturn(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/notifications",
            "/api/v1/notifications/unread",
            "/api/v1/notifications/unread-count",
            "/api/v1/notifications/web",
            "/api/v1/notifications/web/unread",
            "/api/v1/notifications/web/unread-count",
            // The team calendar. A team is the caller's own, and the manager whose team it is holds
            // no administrative role, so the old role gate refused exactly the person it is for.
            "/api/v1/leave-calendar/team?" + RANGE,
            "/api/v1/leave-calendar/web/team?" + RANGE
    })
    void anEmployeeReadsTheirOwnInboxAndTheirOwnTeam(String path) throws Exception {
        asAnEmployeeHoldingNoAdministrativeRole();

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void anEmployeeMarksTheirOwnNotificationsRead() throws Exception {
        asAnEmployeeHoldingNoAdministrativeRole();

        mockMvc.perform(patch("/api/v1/notifications/42/read").with(jwt()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/notifications/web/read?notificationId=42").with(jwt()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/notifications/mark-all-read").with(jwt()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/notifications/web/mark-all-read").with(jwt()).with(csrf()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/leave-calendar/organization?" + RANGE,
            "/api/v1/leave-calendar/department?department=Civil&" + RANGE,
            "/api/v1/leave-calendar/grouped?" + RANGE,
            "/api/v1/leave-calendar/count?date=2026-09-15",
            "/api/v1/leave-calendar/employee/7?" + RANGE,
            "/api/v1/leave-calendar/web/organization?" + RANGE,
            "/api/v1/leave-calendar/web/department?department=Civil&" + RANGE,
            "/api/v1/leave-calendar/web/grouped?" + RANGE,
            "/api/v1/leave-calendar/web/count?date=2026-09-15",
            "/api/v1/leave-calendar/web/employee?employeeId=7&" + RANGE
    })
    void aLeaveAdministratorReadsTheOrganizationWideCalendarOnBothTwins(String path) throws Exception {
        // The phone twin refused them too, because the authority it asked for is issued to nobody
        // at all, administrators included.
        asALeaveAdministrator();

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/leave-calendar/organization?" + RANGE,
            "/api/v1/leave-calendar/department?department=Civil&" + RANGE,
            "/api/v1/leave-calendar/grouped?" + RANGE,
            "/api/v1/leave-calendar/count?date=2026-09-15",
            "/api/v1/leave-calendar/web/organization?" + RANGE,
            "/api/v1/leave-calendar/web/department?department=Civil&" + RANGE,
            "/api/v1/leave-calendar/web/grouped?" + RANGE,
            "/api/v1/leave-calendar/web/count?date=2026-09-15"
    })
    void theOrganizationWideCalendarStaysWithTheAdministrators(String path) throws Exception {
        // The repair must not turn a dead endpoint into an open one. Who is on leave across the
        // whole organization is what the web twin already restricted to the two roles, and this
        // change does not widen it.
        asAnEmployeeHoldingNoAdministrativeRole();

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/notifications",
            "/api/v1/notifications/unread",
            "/api/v1/notifications/unread-count",
            "/api/v1/notifications/web",
            "/api/v1/notifications/web/unread",
            "/api/v1/notifications/web/unread-count",
            "/api/v1/leave-calendar/team?" + RANGE,
            "/api/v1/leave-calendar/web/team?" + RANGE
    })
    void somebodyOutsideTheTenantReachesNoneOfIt(String path) throws Exception {
        asSomebodyOutsideTheTenant();

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isForbidden());
    }
}
