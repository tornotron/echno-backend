package org.tornotron.echno_backend.employee;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that the non-web {@code joinOrganization} route refuses an organization the caller is
 * not entitled to, and that an ordinary administrator acting inside their own organization is
 * unaffected.
 *
 * <p>The route names an organization in its path and
 * {@link EmployeeService#joinOrganization} then loads it by that id. Its guard used to be the
 * bare realm authorities alone, which are global: {@code docs/org-scoped-roles.md} describes
 * {@code employee:create} and {@code employee:admin} as applying everywhere and being scoped to
 * no organization at all. They therefore answer what the caller may do and say nothing about
 * where, while the write lands wherever the path said.
 *
 * <p>That write is the consequential one. Besides the employee row it adds the user to the
 * Keycloak group for the named organization, and that group is what {@code JwtAuthConverter}
 * turns into the {@code ORG_MEMBER_} authority {@code TenantFilter} requires before it will set
 * a tenant at all. The product is the membership the rest of the tenant layer is built on,
 * rather than a credential still waiting to be redeemed.
 *
 * <p>Nothing underneath catches it, for the reason recorded in
 * {@code org.tornotron.echno_backend.projectInviteCode.ProjectInviteCodeTenantGuardTest}:
 * {@code Organization} is the tenant root, outside both the {@code orgFilter} and the
 * fail-closed load listener. The duplicate {@code existsByUserAndOrganization} check inside the
 * service does not stand in for a guard either, because {@code Employee} is filtered, so it
 * evaluates within the caller's own tenant and cannot see a row in another one.
 *
 * <p>The caller modelled here holds {@code employee:create} and is a member of
 * {@link #OWN_ORG}. {@code @orgSecurity} is mocked so the tenant branch is exercised without
 * building JWT group claims; the authority is real, granted on the token, because that half of
 * the expression is what has to keep working.
 */
@WebMvcTest(EmployeeController.class)
@Import(EmployeeMobileJoinGuardTest.TestSecurityConfig.class)
class EmployeeMobileJoinGuardTest {

    private static final long OWN_ORG = 100L;
    private static final long FOREIGN_ORG = 200L;
    private static final long USER_ID = 7L;

    private static final String VALID_BODY = """
            {"designation":"Site Engineer","department":"Civil","status":"active"}
            """;

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

    @BeforeEach
    void callerIsEntitledToTheirOwnOrganizationOnly() {
        when(orgSecurity.isCurrentTenant(OWN_ORG)).thenReturn(true);
        when(orgSecurity.isCurrentTenant(FOREIGN_ORG)).thenReturn(false);
    }

    @Test
    void join_intoAnotherOrganization_isForbiddenAndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/v1/employee/joinOrganization/" + USER_ID + "/" + FOREIGN_ORG)
                        .with(jwt().authorities(new SimpleGrantedAuthority("employee:create")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        // The refusal has to land before the service runs. A 403 over a completed join would
        // leave the employee row and, worse, the Keycloak group membership behind.
        verifyNoInteractions(employeeService);
    }

    @Test
    void join_holdingTheGlobalAdminAuthorityIntoAnotherOrganization_isAlsoForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/employee/joinOrganization/" + USER_ID + "/" + FOREIGN_ORG)
                        .with(jwt().authorities(new SimpleGrantedAuthority("employee:admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(employeeService);
    }

    @Test
    void join_intoTheCallersOwnOrganization_isStillAllowed() throws Exception {
        mockMvc.perform(post("/api/v1/employee/joinOrganization/" + USER_ID + "/" + OWN_ORG)
                        .with(jwt().authorities(new SimpleGrantedAuthority("employee:create")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    /**
     * The authority half of the expression still has to be satisfied on its own. Being scoped to
     * the organization is not a substitute for holding the permission, or every member of an
     * organization could add employees to it.
     */
    @Test
    void join_intoTheCallersOwnOrganizationWithoutTheAuthority_isForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/employee/joinOrganization/" + USER_ID + "/" + OWN_ORG)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(employeeService);
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
