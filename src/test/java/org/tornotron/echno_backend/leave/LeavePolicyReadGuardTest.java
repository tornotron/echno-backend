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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The leave-policy listing takes an organization from the path, and the form that spends those
 * policies takes an employee.
 *
 * <p>The listing's guard established a role in the caller's own tenant and never read the id it was
 * given. The service then asked only whether the organization existed, which is a check on the
 * deployment rather than on the caller. {@code LeavePolicy} carries the {@code orgFilter}, so a
 * foreign id returned an empty list rather than another tenant's accrual rates and encashment
 * rules, which puts this well below #687 in consequence: what is left is a parameter that decides
 * what is answered with nothing establishing entitlement to it, and an {@code existsById} that
 * reads as though it were doing tenant work. The check it wants is the one #687 introduced.
 *
 * <p>The applicable-policies read moves with it for a different reason.
 * {@code LeaveRequestController} lets a system or HR admin raise a leave request on an employee's
 * behalf, while this read, which is what the request form offers them, was self-only. The caller
 * entitled to finish the job could not read what the form needed. That is #666 exactly, and the
 * expression the two now share is the one the create endpoint already used.
 */
@WebMvcTest({LeavePolicyController.class, LeavePolicyControllerWeb.class})
@Import(LeavePolicyReadGuardTest.TestSecurityConfig.class)
class LeavePolicyReadGuardTest {

    private static final long OWN_ORG = 100L;
    private static final long FOREIGN_ORG = 200L;
    private static final long SELF = 7L;
    private static final long COLLEAGUE = 8L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LeavePolicyService policyService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here
    // because .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    /** An hr-admin of their own organization and of no other. */
    private void callerIsAnHrAdmin() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).thenReturn(true);
        when(orgSecurity.isCurrentTenant(OWN_ORG)).thenReturn(true);
        when(orgSecurity.isCurrentTenant(FOREIGN_ORG)).thenReturn(false);
        when(orgSecurity.isSelfOrHasAnyOrgRole(COLLEAGUE, "system-admin", "hr-admin")).thenReturn(true);
    }

    /** An ordinary employee, reading their own. */
    private void callerIsAnOrdinaryEmployee() {
        when(orgSecurity.isSelfInCurrentTenant(SELF)).thenReturn(true);
        when(orgSecurity.isSelfOrHasAnyOrgRole(SELF, "system-admin", "hr-admin")).thenReturn(true);
    }

    @Test
    void theListingIsRefusedForAnOrganizationTheCallerIsNotEntitledTo() throws Exception {
        callerIsAnHrAdmin();

        mockMvc.perform(get("/api/v1/leave-policies/organization/" + FOREIGN_ORG).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(policyService);
    }

    @Test
    void theListingStillAnswersForTheCallersOwnOrganization() throws Exception {
        callerIsAnHrAdmin();

        mockMvc.perform(get("/api/v1/leave-policies/organization/" + OWN_ORG).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void anAdministratorCanReadThePoliciesTheRequestFormWillOffer() throws Exception {
        callerIsAnHrAdmin();

        mockMvc.perform(get("/api/v1/leave-policies/employee/" + COLLEAGUE).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void anAdministratorCanReadThemOnTheWebTwinToo() throws Exception {
        callerIsAnHrAdmin();

        mockMvc.perform(get("/api/v1/leave-policies/web/employee")
                        .param("employeeId", String.valueOf(COLLEAGUE)).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void anEmployeeStillReadsTheirOwnApplicablePolicies() throws Exception {
        callerIsAnOrdinaryEmployee();

        mockMvc.perform(get("/api/v1/leave-policies/employee/" + SELF).with(jwt()))
                .andExpect(status().isOk());
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
