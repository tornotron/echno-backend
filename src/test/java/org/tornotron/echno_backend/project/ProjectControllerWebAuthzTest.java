package org.tornotron.echno_backend.project;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import org.tornotron.echno_backend.common.payload.PayloadValidator;

/**
 * Web-slice authorization tests for the read endpoints on ProjectControllerWeb.
 * They lock in the guard that a caller may read projects when they are a member of
 * the current tenant.
 *
 * <p>These used to assert a second branch, added by {@code eec6c37}, that let a
 * role-holder who is not recorded as a member read as well. That branch passed only
 * because @orgSecurity is mocked here, which lets the two guards be set
 * independently; no real request can be in that state, because TenantFilter resolves
 * an organization from ORG_MEMBER_ authorities alone and both guards refuse a null
 * organization. The invariant now lives where it can be measured rather than mocked,
 * in TenantFilterTest.theRoleGuardCannotSucceedWhereTheMembershipGuardFails, and the
 * branch it disproved is gone from the controller. See #709.
 */
@WebMvcTest(ProjectControllerWeb.class)
@Import({ProjectControllerWebAuthzTest.TestSecurityConfig.class, JsonPartBinder.class,
        PayloadValidator.class})
class ProjectControllerWebAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here
    // because .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    // RPTExchangeFilter also depends on this cache; mocked for the same reason.
    @MockitoBean
    private RPTCache rptCache;

    @Test
    void readAllProjects_isOk_forAMemberWithoutAnElevatedRole() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
        when(projectService.getAllProjects(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/project/web").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void readAllProjects_isOk_forAMemberWhoAlsoHoldsAnElevatedRole() throws Exception {
        // The ordinary shape of an administrator's request: the role never arrives without the
        // membership, so this is what the removed second clause was really describing.
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(projectService.getAllProjects(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/project/web").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void readAllProjects_isForbidden_forACallerWithNoMembership() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);

        mockMvc.perform(get("/api/v1/project/web").with(jwt()))
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
