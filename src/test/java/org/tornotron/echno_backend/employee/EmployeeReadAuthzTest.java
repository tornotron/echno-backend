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
import org.tornotron.echno_backend.employee.dto.EmployeeDto;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read one employee record, on both twins.
 *
 * <p>The web twin gated the read on the system-admin, hr-admin or project-manager role while the
 * PATCH beside it gated on {@code isSelfOrHasAnyOrgRole}, so a person could edit their own record
 * and then be refused reading it back. That is the shape commit eec6c37 fixed on projects.
 *
 * <p>The mobile twin refused everybody: {@code employee:read} and {@code employee:admin} are bare
 * {@code resource:scope} authorities, which {@code JwtAuthConverter} mints only from the
 * authorization claim of an RPT, and the realm carries no authorization scopes to put there. Same
 * phantom-guard mechanism as #684, repaired the same way rather than deleted.
 */
@WebMvcTest({EmployeeController.class, EmployeeControllerWeb.class})
@Import(EmployeeReadAuthzTest.TestSecurityConfig.class)
class EmployeeReadAuthzTest {

    private static final long SELF = 7L;
    private static final long SOMEONE_ELSE = 8L;

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

    /** A plain member: no elevated role anywhere, self only on their own record. */
    private void plainMemberWhoIs(long selfId) {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
        when(orgSecurity.isSelfOrHasAnyOrgRole(anyLong(), any(String[].class))).thenReturn(false);
        when(orgSecurity.isSelfOrHasAnyOrgRole(eq(selfId), any(String[].class))).thenReturn(true);
        when(employeeService.displayAnEmployee(anyLong())).thenReturn(new EmployeeDto());
    }

    @Test
    void webRead_isOk_forTheEmployeeThemselves() throws Exception {
        // The read has to be at least as open as the PATCH beside it, which is
        // isSelfOrHasAnyOrgRole. Before the repair this was 403 while the PATCH was 200.
        plainMemberWhoIs(SELF);

        mockMvc.perform(get("/api/v1/employee/web/" + SELF).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void webRead_isForbidden_forAPlainMemberReadingAColleague() throws Exception {
        // The repair adds self and nothing else: a member with no elevated role still cannot
        // open somebody else's record, which carries salary, date of birth and address.
        plainMemberWhoIs(SELF);

        mockMvc.perform(get("/api/v1/employee/web/" + SOMEONE_ELSE).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void webRead_isOk_forARoleHolderReadingAColleague() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(orgSecurity.isSelfOrHasAnyOrgRole(anyLong(), any(String[].class))).thenReturn(true);
        when(employeeService.displayAnEmployee(anyLong())).thenReturn(new EmployeeDto());

        mockMvc.perform(get("/api/v1/employee/web/" + SOMEONE_ELSE).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void mobileRead_isOk_forTheEmployeeThemselves() throws Exception {
        // Against the phantom guard this was 403 for every caller in every environment.
        plainMemberWhoIs(SELF);

        mockMvc.perform(get("/api/v1/employee/" + SELF).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void mobileRead_isForbidden_forAPlainMemberReadingAColleague() throws Exception {
        plainMemberWhoIs(SELF);

        mockMvc.perform(get("/api/v1/employee/" + SOMEONE_ELSE).with(jwt()))
                .andExpect(status().isForbidden());
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
