package org.tornotron.echno_backend.employee;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
import org.tornotron.echno_backend.employee.dto.EmployeeDto;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The five guards on the mobile {@code EmployeeController} that named an authority no realm can
 * issue, and what each answers now.
 *
 * <p>{@code employee:read}, {@code employee:update} and {@code employee:delete} are bare
 * {@code resource:scope} authorities. {@code JwtAuthConverter} mints those in one place,
 * {@code extractPermissions}, which reads the {@code authorization} claim of an RPT, so each needs
 * a Keycloak Authorization Services resource named {@code employee} carrying the matching scope.
 * The only automated provisioner registers a scopeless Default Resource and a scopeless Default
 * Permission, and a permission with no scopes yields no {@code resource:scope} authority at all.
 * Every one of these endpoints therefore refused every caller from the day the annotation was
 * written. Same mechanism as #641 and #684, repaired the same way as #710 rather than deleted.
 * See #716.
 *
 * <p>Each endpoint is tested twice over, and the pair is the point. A caller holding the old
 * authority and nothing else must now be refused, or the repair has widened the surface instead of
 * moving it; a caller holding the org role and none of the old authorities must now be admitted,
 * or the endpoint is still dead. The first half of each pair is what a realm cannot actually
 * produce, granted here directly on the token so the assertion means something.
 *
 * <p>What this proves and what it does not: {@code @orgSecurity} is a mock, so these tests fix the
 * expression each endpoint evaluates and the roles it names. They say nothing about how
 * {@code OrganizationSecurityService} resolves a role, which
 * {@code KeycloakOrgMembershipInvariantIT} and {@code TenantIsolationIT} cover against a real
 * context.
 */
@WebMvcTest(EmployeeController.class)
@Import(EmployeeMobileGuardTest.TestSecurityConfig.class)
class EmployeeMobileGuardTest {

    private static final long OWN_ORG = 100L;
    private static final long FOREIGN_ORG = 200L;
    private static final long EMPLOYEE = 7L;

    private static final String PATCH_BODY = """
            {"department":"Civil"}
            """;

    private static final String BATCH_BODY = """
            [{"id":7,"updates":{"department":"Civil"}}]
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmployeeService employeeService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here because
    // .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    /** Nobody: no org role anywhere, not the employee in question, no organization entitlement. */
    private void callerHoldsNoRole() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
        when(orgSecurity.isSelfOrHasAnyOrgRole(anyLong(), any(String[].class))).thenReturn(false);
        when(orgSecurity.isCurrentTenant(anyLong())).thenReturn(false);
    }

    /** An HR admin of the organization the session is scoped to, holding no Keycloak authority. */
    private void callerIsAnHrAdminOf(long orgId) {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(orgSecurity.isSelfOrHasAnyOrgRole(anyLong(), any(String[].class))).thenReturn(true);
        when(orgSecurity.isCurrentTenant(anyLong())).thenReturn(false);
        when(orgSecurity.isCurrentTenant(eq(orgId))).thenReturn(true);
    }

    private void listingsAnswer() {
        Page<EmployeeDto> page = new PageImpl<>(List.of(new EmployeeDto()));
        // any() rather than anyString(): the controller passes null for all three filters, and
        // anyString() does not match a null argument.
        when(employeeService.displayAllEmployees(anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(page);
        when(employeeService.displayEmployeesByOrganization(anyLong()))
                .thenReturn(List.of(new EmployeeDto()));
    }

    // ---- GET / : the directory listing -------------------------------------------------

    @Test
    void theListing_isRefusedToAHolderOfTheOldReadAuthority() throws Exception {
        callerHoldsNoRole();

        mockMvc.perform(get("/api/v1/employee")
                        .with(jwt().authorities(new SimpleGrantedAuthority("employee:read"),
                                new SimpleGrantedAuthority("employee:admin"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(employeeService);
    }

    @Test
    void theListing_answersARoleHolderWhoHoldsNoAuthorityAtAll() throws Exception {
        callerIsAnHrAdminOf(OWN_ORG);
        listingsAnswer();

        mockMvc.perform(get("/api/v1/employee").with(jwt()))
                .andExpect(status().isOk());
    }

    // ---- GET /organization/{id} : the same directory, organization named ----------------

    @Test
    void theOrganizationListing_isRefusedToAHolderOfTheOldReadAuthority() throws Exception {
        callerHoldsNoRole();

        mockMvc.perform(get("/api/v1/employee/organization/" + OWN_ORG)
                        .with(jwt().authorities(new SimpleGrantedAuthority("employee:read"),
                                new SimpleGrantedAuthority("employee:admin"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(employeeService);
    }

    /**
     * The organization clause is the part that changed shape rather than merely being substituted.
     * The old guard asked {@code isMember(#id)}, which a caller who belongs to two organizations
     * satisfies for either of them, so the directory of the organization their session was not
     * scoped to came back. {@code isCurrentTenant} refuses it, and it has to, because
     * {@code Organization} is the tenant root and no ambient defence covers an id the caller
     * supplies for it.
     */
    @Test
    void theOrganizationListing_isRefusedForAnOrganizationThatIsNotTheCurrentTenant() throws Exception {
        callerIsAnHrAdminOf(OWN_ORG);

        mockMvc.perform(get("/api/v1/employee/organization/" + FOREIGN_ORG).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(employeeService);
    }

    @Test
    void theOrganizationListing_answersForTheCallersOwnOrganization() throws Exception {
        callerIsAnHrAdminOf(OWN_ORG);
        listingsAnswer();

        mockMvc.perform(get("/api/v1/employee/organization/" + OWN_ORG).with(jwt()))
                .andExpect(status().isOk());
    }

    // ---- PATCH /{id} : maintaining one record ------------------------------------------

    @Test
    void theUpdate_isRefusedToAHolderOfTheOldUpdateAuthority() throws Exception {
        callerHoldsNoRole();

        mockMvc.perform(patch("/api/v1/employee/" + EMPLOYEE)
                        .with(jwt().authorities(new SimpleGrantedAuthority("employee:update"),
                                new SimpleGrantedAuthority("employee:admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PATCH_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(employeeService);
    }

    @Test
    void theUpdate_answersTheEmployeeThemselvesOrAnAdmin() throws Exception {
        callerIsAnHrAdminOf(OWN_ORG);

        mockMvc.perform(patch("/api/v1/employee/" + EMPLOYEE)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PATCH_BODY))
                .andExpect(status().isOk());
    }

    // ---- PATCH /batch : maintaining a set ----------------------------------------------

    @Test
    void theBatchUpdate_isRefusedToAHolderOfTheOldUpdateAuthority() throws Exception {
        callerHoldsNoRole();

        mockMvc.perform(patch("/api/v1/employee/batch")
                        .with(jwt().authorities(new SimpleGrantedAuthority("employee:update"),
                                new SimpleGrantedAuthority("employee:admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BATCH_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(employeeService);
    }

    /**
     * The ids a batch acts on arrive in the body, so no guard can bind to them and the roles are
     * what decide. Recording that deliberately: a later reader should not add a {@code #id} clause
     * here expecting it to bind to anything.
     */
    @Test
    void theBatchUpdate_answersAnAdminOfTheCurrentOrganization() throws Exception {
        callerIsAnHrAdminOf(OWN_ORG);

        mockMvc.perform(patch("/api/v1/employee/batch")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BATCH_BODY))
                .andExpect(status().isOk());
    }

    // ---- DELETE /{id} : ending a membership ---------------------------------------------

    @Test
    void theDelete_isRefusedToAHolderOfTheOldDeleteAuthority() throws Exception {
        callerHoldsNoRole();

        mockMvc.perform(delete("/api/v1/employee/" + EMPLOYEE)
                        .with(jwt().authorities(new SimpleGrantedAuthority("employee:delete"),
                                new SimpleGrantedAuthority("employee:admin"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(employeeService);
    }

    @Test
    void theDelete_answersAnAdminOfTheCurrentOrganization() throws Exception {
        callerIsAnHrAdminOf(OWN_ORG);

        mockMvc.perform(delete("/api/v1/employee/" + EMPLOYEE).with(jwt()))
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
