package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A site manager holding no administrative role reaches the leave approval workflow on both twins.
 *
 * <p>This is the end-to-end proof of the change, driven through the real security pipeline rather
 * than against the annotation text. Every one of these paths answered 403 to this caller before it,
 * for one of two reasons. Reject, delegate, the trail reads and the can-approve check on the phone
 * twin asked for {@code hasAuthority('leave:approve')}, {@code 'leave:read'} or
 * {@code 'leave:admin'}, which this realm defines no mechanism to issue, so they refused everybody.
 * Approve, both twins' web equivalents and the pending-approvals queue asked for the system-admin
 * or hr-admin role, which an approver does not hold: an approval chain is built by walking the
 * employee's management line, so the holder of the decision is a manager.
 *
 * <p>The counterpart matters as much. Membership is a coarse gate, and it is deliberately not the
 * whole answer: {@code LeaveApprovalService} refuses anybody who is not the request's current
 * approver, and {@code LeaveApproverQueueTest} and {@code LeaveApprovalTrailAccessTest} pin that.
 * What this test adds is that a caller who is not a member of the tenant at all still gets nowhere,
 * so the guard was widened to the workflow and not to everybody.
 *
 * <p>All four controllers share one web slice so the suite gains one Spring context rather than
 * four; see {@code EchnoBackendApplicationTests} for why the cached-context budget is watched.
 */
@WebMvcTest({
        LeaveApprovalController.class,
        LeaveApprovalControllerWeb.class,
        LeaveRequestController.class,
        LeaveRequestControllerWeb.class})
@Import(LeaveApprovalWorkflowAuthzTest.TestSecurityConfig.class)
class LeaveApprovalWorkflowAuthzTest {

    private static final String ACTION_BODY = "{\"comments\":\"Approved, plan the handover\"}";
    private static final String DELEGATE_BODY = "{\"comments\":\"Away next week\",\"delegateToId\":9}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LeaveApprovalService approvalService;

    @MockitoBean
    private LeaveRequestService requestService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here because
    // .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @BeforeEach
    void stubTheWorkflow() {
        when(approvalService.approve(anyLong(), any())).thenReturn(new LeaveRequestDto());
        when(approvalService.reject(anyLong(), any())).thenReturn(new LeaveRequestDto());
        when(approvalService.delegate(anyLong(), any())).thenReturn(new LeaveRequestDto());
        when(approvalService.getApprovalHistory(anyLong())).thenReturn(List.of());
        when(approvalService.getApprovalChain(anyLong())).thenReturn(List.of());
        when(approvalService.canApprove(anyLong())).thenReturn(true);
        when(requestService.getPendingApprovals()).thenReturn(List.of());
        when(requestService.getPendingApprovalCount()).thenReturn(0L);
        when(requestService.getRequestsByApprover()).thenReturn(List.of());
    }

    /** A site manager: a member of the tenant, holding neither administrative leave role. */
    private void asAnApproverHoldingNoAdministrativeRole() {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).thenReturn(false);
    }

    private void asSomebodyOutsideTheTenant() {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).thenReturn(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/leave-approvals/requests/42/history",
            "/api/v1/leave-approvals/requests/42/chain",
            "/api/v1/leave-approvals/requests/42/can-approve",
            "/api/v1/leave-approvals/web/history?requestId=42",
            "/api/v1/leave-approvals/web/chain?requestId=42",
            "/api/v1/leave-approvals/web/can-approve?requestId=42",
            "/api/v1/leave-requests/pending-approvals",
            "/api/v1/leave-requests/pending-approvals/count",
            "/api/v1/leave-requests/web/pending-approvals",
            "/api/v1/leave-requests/web/pending-approvals/count",
            "/api/v1/leave-requests/web/approver"
    })
    void anApproverMayReadTheirQueueAndTheTrailOfARequest(String path) throws Exception {
        asAnApproverHoldingNoAdministrativeRole();

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/leave-approvals/requests/42/approve",
            "/api/v1/leave-approvals/requests/42/reject",
            "/api/v1/leave-approvals/web/approve?requestId=42",
            "/api/v1/leave-approvals/web/reject?requestId=42"
    })
    void anApproverMayApproveAndRejectFromEitherTwin(String path) throws Exception {
        asAnApproverHoldingNoAdministrativeRole();

        mockMvc.perform(post(path).with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACTION_BODY))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/leave-approvals/requests/42/delegate",
            "/api/v1/leave-approvals/web/delegate?requestId=42"
    })
    void anApproverMayHandTheirTurnToADelegate(String path) throws Exception {
        asAnApproverHoldingNoAdministrativeRole();

        mockMvc.perform(post(path).with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DELEGATE_BODY))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/leave-approvals/requests/42/history",
            "/api/v1/leave-approvals/requests/42/can-approve",
            "/api/v1/leave-approvals/web/chain?requestId=42",
            "/api/v1/leave-requests/pending-approvals",
            "/api/v1/leave-requests/web/pending-approvals/count"
    })
    void somebodyOutsideTheTenantIsStillRefused(String path) throws Exception {
        // Membership replaced a role gate that could not express the rule. It did not replace it
        // with nothing: a caller with no place in this organization gets no further than before.
        asSomebodyOutsideTheTenant();

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void somebodyOutsideTheTenantMayNotApprove() throws Exception {
        asSomebodyOutsideTheTenant();

        mockMvc.perform(post("/api/v1/leave-approvals/requests/42/approve").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACTION_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void aQueueCallThatStillSendsAnApproverIdIsServedTheCallersOwnQueue() throws Exception {
        // The deployed client sends approverId on every one of these. Spring drops a query
        // parameter no handler declares, so the fix ships without waiting on a frontend release.
        asAnApproverHoldingNoAdministrativeRole();

        mockMvc.perform(get("/api/v1/leave-requests/web/pending-approvals")
                        .param("approverId", "99")
                        .with(jwt()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(requestService).getPendingApprovals();
    }

    @Test
    void aCanApproveCallThatStillSendsAnEmployeeIdIsAnsweredForTheCaller() throws Exception {
        asAnApproverHoldingNoAdministrativeRole();

        mockMvc.perform(get("/api/v1/leave-approvals/web/can-approve")
                        .param("requestId", "42")
                        .param("employeeId", "99")
                        .with(jwt()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(approvalService).canApprove(42L);
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            return http.build();
        }
    }
}
