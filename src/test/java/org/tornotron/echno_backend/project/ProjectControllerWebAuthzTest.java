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
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice authorization tests for the read endpoints on ProjectControllerWeb.
 * They lock in the guard that a caller may read projects when they are either a
 * member of the current tenant OR hold an elevated org role (system-admin /
 * project-manager) for it. The role branch matters because the write endpoints on
 * this same controller gate on the role alone: without it a role-holder who is not
 * recorded as a member (for example the bootstrap admin) could create and edit
 * projects yet get 403 listing them. @orgSecurity is mocked so each branch is
 * exercised independently; if a read @PreAuthorize is ever narrowed back to
 * membership only, the role-without-membership test fails.
 */
@WebMvcTest(ProjectControllerWeb.class)
@Import(ProjectControllerWebAuthzTest.TestSecurityConfig.class)
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

    @Test
    void readAllProjects_isOk_forAMemberWithoutAnElevatedRole() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
        when(projectService.getAllProjects(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/project/web").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void readAllProjects_isOk_forARoleHolderThatIsNotRecordedAsAMember() throws Exception {
        // The fix: an org system-admin / project-manager can read even without the
        // ORG_MEMBER_ authority, matching how create/update/delete already gate.
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(projectService.getAllProjects(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/project/web").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void readAllProjects_isForbidden_forACallerWithNeitherMembershipNorRole() throws Exception {
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
