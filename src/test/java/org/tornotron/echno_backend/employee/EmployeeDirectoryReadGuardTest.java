package org.tornotron.echno_backend.employee;

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
 * Two directory reads that took an id from the caller and then checked something else.
 *
 * <p>The subordinate list asked for one of three organization-wide roles and never looked at the
 * manager it was given. Reporting lines are set on {@code Employee.manager} and have nothing to do
 * with the Keycloak-derived role set, so a site supervisor who is somebody's manager in the data
 * and holds no {@code project-manager} role was refused their own direct reports, while any holder
 * of that role could name any other manager and receive their whole team as full
 * {@code EmployeeDto}s, salary and date of birth included. The same manager-versus-role mismatch
 * {@code AttendanceSecurityService.canDecideApproval} exists to answer, and that #685 found in the
 * leave approval chain.
 *
 * <p>The manager directory names an organization the guard never reads. {@code Employee} carries
 * the {@code orgFilter}, so a foreign id returns an empty list rather than another tenant's
 * managers, which is why this half is a redundant parameter rather than a leak. It is still a
 * parameter that decides what is answered while nothing establishes the caller is entitled to it,
 * and the check for that is the one #687 introduced.
 */
@WebMvcTest(EmployeeControllerWeb.class)
@Import(EmployeeDirectoryReadGuardTest.TestSecurityConfig.class)
class EmployeeDirectoryReadGuardTest {

    private static final long OWN_ORG = 100L;
    private static final long FOREIGN_ORG = 200L;
    private static final long CALLER_AS_MANAGER = 7L;
    private static final long ANOTHER_MANAGER = 8L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmployeeService employeeService;

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
     * A line manager: people report to them in the data, and they hold none of the three roles the
     * old guard asked for.
     */
    private void callerIsALineManagerWithNoRole() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin", "project-manager"))
                .thenReturn(false);
        when(orgSecurity.isSelfOrHasAnyOrgRole(CALLER_AS_MANAGER, "system-admin", "hr-admin"))
                .thenReturn(true);
        when(orgSecurity.isSelfOrHasAnyOrgRole(ANOTHER_MANAGER, "system-admin", "hr-admin"))
                .thenReturn(false);
    }

    /** A project-manager: holds the role, is not the manager named. */
    private void callerIsAProjectManager() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin", "project-manager"))
                .thenReturn(true);
        when(orgSecurity.isSelfOrHasAnyOrgRole(ANOTHER_MANAGER, "system-admin", "hr-admin"))
                .thenReturn(false);
        when(orgSecurity.isCurrentTenant(OWN_ORG)).thenReturn(true);
        when(orgSecurity.isCurrentTenant(FOREIGN_ORG)).thenReturn(false);
    }

    @Test
    void aLineManagerCanReadTheirOwnDirectReports() throws Exception {
        callerIsALineManagerWithNoRole();

        mockMvc.perform(get("/api/v1/employee/web/managerId/" + CALLER_AS_MANAGER + "/subordinates")
                        .with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void anotherManagersTeamIsRefusedToARoleHolderWhoIsNotThatManager() throws Exception {
        callerIsAProjectManager();

        mockMvc.perform(get("/api/v1/employee/web/managerId/" + ANOTHER_MANAGER + "/subordinates")
                        .with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(employeeService);
    }

    @Test
    void theManagerDirectoryIsRefusedForAnOrganizationTheCallerIsNotEntitledTo() throws Exception {
        callerIsAProjectManager();

        mockMvc.perform(get("/api/v1/employee/web/managers/organizationId/" + FOREIGN_ORG).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(employeeService);
    }

    @Test
    void theManagerDirectoryStillAnswersForTheCallersOwnOrganization() throws Exception {
        callerIsAProjectManager();

        mockMvc.perform(get("/api/v1/employee/web/managers/organizationId/" + OWN_ORG).with(jwt()))
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
