package org.tornotron.echno_backend.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.TeamAccess;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice authorization tests locking in the phase-0 @PreAuthorize guards on
 * UserController (the fixes that closed the open-registration cross-tenant chain).
 * Method security is enabled by a minimal test security config; @access and
 * @teamAccess are mocked so a caller the matrix refuses is forbidden and one it
 * allows is let through. Regression guard: if a @PreAuthorize is ever dropped, these fail.
 */
@WebMvcTest(UserController.class)
@Import(UserControllerAuthzTest.TestSecurityConfig.class)
class UserControllerAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean(name = "access")
    private AccessGuard access;

    @MockitoBean(name = "teamAccess")
    private TeamAccess teamAccess;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here
    // because .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    // RPTExchangeFilter also depends on this cache; mocked for the same reason.
    @MockitoBean
    private RPTCache rptCache;

    @Test
    void readAllUsers_isForbidden_forANonAdminCaller() throws Exception {
        when(access.can(anyString(), anyString())).thenReturn(false);

        mockMvc.perform(get("/api/v1/user/all").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void readAllUsers_isOk_forAnAdminCaller() throws Exception {
        when(access.can("team", "read-user-accounts")).thenReturn(true);
        when(teamAccess.acrossOrganization("read-user-accounts")).thenReturn(true);
        when(userService.getAllUsers(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/user/all").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void deleteUser_isForbidden_forANonAdminCaller() throws Exception {
        when(access.can(anyString(), anyString())).thenReturn(false);

        mockMvc.perform(delete("/api/v1/user/5").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_isForbidden_forACallerWhoOnlyListsAccounts() throws Exception {
        when(access.can(anyString(), anyString())).thenReturn(false);
        when(access.can("team", "read-user-accounts")).thenReturn(true);
        when(teamAccess.acrossOrganization("read-user-accounts")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/user/5").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_isOk_forACallerWhoManagesAccounts() throws Exception {
        when(access.can("team", "manage-user-accounts")).thenReturn(true);
        when(teamAccess.acrossOrganization("manage-user-accounts")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/user/5").with(jwt()))
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
