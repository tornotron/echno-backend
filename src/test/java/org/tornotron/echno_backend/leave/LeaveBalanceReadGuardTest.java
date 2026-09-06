package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read a leave balance, on both twins.
 *
 * <p>The controller pair pointed in two directions at once. The balance reads were gated on the
 * system-admin and hr-admin roles, so an employee could not find out how many days they had left,
 * which is the first question anybody asks a leave module. The summary beside them was gated on
 * bare tenant membership, so the same employee could read any colleague's entitlement and remaining
 * days by passing their id. Repairing one and not the other leaves the module wrong in the opposite
 * direction, so both move here.
 *
 * <p>The transaction ledger moves with them, in the third direction. It was self-only, which left
 * the balance-management screen unable to show the transactions behind a balance an administrator
 * had just adjusted: the same shape as #666, where the endpoint named in the issue was repaired and
 * the caller was still unable to finish the job. The expression the whole set lands on,
 * {@code isSelfOrHasAnyOrgRole}, is the one {@code LeaveRequestController} already uses for the
 * request these balances are spent by.
 *
 * <p>{@code @orgSecurity} is mocked so the branches are exercised without building JWT group
 * claims. The service is mocked and its return value is irrelevant; what matters is whether the
 * request reaches it.
 */
@WebMvcTest({LeaveBalanceController.class, LeaveBalanceControllerWeb.class})
@Import(LeaveBalanceReadGuardTest.TestSecurityConfig.class)
class LeaveBalanceReadGuardTest {

    private static final long SELF = 7L;
    private static final long COLLEAGUE = 8L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LeaveBalanceService balanceService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here
    // because .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    /**
     * An ordinary employee: themselves and nobody else, holding neither administrative role.
     *
     * <p>Membership is stubbed true because it is what the caller genuinely holds. That is what
     * makes the summary case a measurement rather than an artefact: the old guard asked for
     * membership and nothing else, so the colleague's summary really was returned to this caller.
     */
    private void callerIsAnOrdinaryEmployee() {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.isSelfOrHasAnyOrgRole(SELF, "system-admin", "hr-admin")).thenReturn(true);
        when(orgSecurity.isSelfOrHasAnyOrgRole(COLLEAGUE, "system-admin", "hr-admin")).thenReturn(false);
    }

    /** An hr-admin: the role branch answers for anybody, before the change and after it. */
    private void callerIsAnHrAdmin() {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).thenReturn(true);
        when(orgSecurity.isSelfOrHasAnyOrgRole(COLLEAGUE, "system-admin", "hr-admin")).thenReturn(true);
    }

    @Test
    void anEmployeeCanReadTheirOwnBalances() throws Exception {
        callerIsAnOrdinaryEmployee();

        mockMvc.perform(get("/api/v1/leave-balances/employee/" + SELF).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void anEmployeeCanReadTheirOwnBalancesOnTheWebTwin() throws Exception {
        callerIsAnOrdinaryEmployee();

        mockMvc.perform(get("/api/v1/leave-balances/web").param("employeeId", String.valueOf(SELF))
                        .with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void anEmployeeCanReadTheirOwnSummary() throws Exception {
        callerIsAnOrdinaryEmployee();

        mockMvc.perform(get("/api/v1/leave-balances/employee/" + SELF + "/summary").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void aColleaguesSummaryIsRefusedToAnOrdinaryEmployee() throws Exception {
        callerIsAnOrdinaryEmployee();

        mockMvc.perform(get("/api/v1/leave-balances/employee/" + COLLEAGUE + "/summary").with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(balanceService);
    }

    @Test
    void aColleaguesSummaryIsRefusedOnTheWebTwinToo() throws Exception {
        callerIsAnOrdinaryEmployee();

        mockMvc.perform(get("/api/v1/leave-balances/web/summary")
                        .param("employeeId", String.valueOf(COLLEAGUE)).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(balanceService);
    }

    @Test
    void anAdministratorCanReadTheLedgerBehindABalanceTheyAdjusted() throws Exception {
        callerIsAnHrAdmin();

        mockMvc.perform(get("/api/v1/leave-balances/employee/" + COLLEAGUE + "/transactions")
                        .with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void anAdministratorCanStillReadAnyEmployeesBalances() throws Exception {
        callerIsAnHrAdmin();

        mockMvc.perform(get("/api/v1/leave-balances/employee/" + COLLEAGUE).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void recalculateStaysWithTheAdministrators() throws Exception {
        // Deliberately not moved. It is the one route in the pair that is written as a command
        // rather than a read, and an employee triggering a recalculation of their own figures is
        // a different decision from an employee being told what they are.
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).thenReturn(false);

        mockMvc.perform(post("/api/v1/leave-balances/employee/" + SELF + "/recalculate").with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(balanceService);
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
