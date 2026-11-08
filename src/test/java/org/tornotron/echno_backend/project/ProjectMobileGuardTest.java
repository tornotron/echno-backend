package org.tornotron.echno_backend.project;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The mobile {@code ProjectController}, which the Android app reaches with an
 * {@code X-Organization-Id} header, answers as its web twin does.
 *
 * <p>Its guards named {@code project:read} and the like, bare {@code resource:scope} authorities
 * that only an RPT carrying authorization scopes could mint, and the realm defines none. A member
 * of the organization calling {@code GET /api/v1/project} was therefore refused with a 403 naming
 * {@code project:read}, which is how the Android work found it. The guards now carry the web
 * twin's expressions: reads are open to any member of the tenant, and the writes belong to a
 * system admin or project manager.
 *
 * <p>The role list is stubbed exactly. The broad {@code any(String[].class)} stub answers false,
 * so a guard that drifted to a different list is refused here rather than passing on a Mockito
 * default.
 */
@WebMvcTest(ProjectController.class)
@Import(ProjectMobileGuardTest.TestSecurityConfig.class)
class ProjectMobileGuardTest {

    private static final long PROJECT = 42L;
    private static final long EMPLOYEE = 7L;

    /** The pair the web twin's project writes name. */
    private static final String[] WRITE_ROLES = {"system-admin", "project-manager"};

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private JsonPartBinder jsonPartBinder;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here because
    // .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    /** No membership of the tenant and no role in it. */
    private void callerIsAnOutsider() {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
    }

    /** An ordinary member of the tenant, holding no org role. */
    private void callerIsAPlainMember() {
        callerIsAnOutsider();
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
    }

    /** A project manager: a member who also satisfies the write pair. */
    private void callerIsAProjectManager() {
        callerIsAPlainMember();
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(WRITE_ROLES)).thenReturn(true);
    }

    @Test
    void list_asAMemberOfTheTenant_isAllowed() throws Exception {
        callerIsAPlainMember();
        when(projectService.getAllProjects(anyInt(), anyInt())).thenReturn(Page.empty());

        // The header names an organization the token holds, as the app sends it.
        mockMvc.perform(get("/api/v1/project").header("X-Organization-Id", "2")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ORG_MEMBER_2"))))
                .andExpect(status().isOk());
    }

    @Test
    void list_asSomebodyOutsideTheTenant_isForbidden() throws Exception {
        callerIsAnOutsider();

        mockMvc.perform(get("/api/v1/project").with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(projectService);
    }

    @Test
    void list_unauthenticated_isRefused() throws Exception {
        mockMvc.perform(get("/api/v1/project"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(projectService, orgSecurity);
    }

    @Test
    void read_asAMemberOfTheTenant_isAllowed() throws Exception {
        callerIsAPlainMember();

        mockMvc.perform(get("/api/v1/project/{id}", PROJECT).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void employees_asAMemberOfTheTenant_isAllowed() throws Exception {
        callerIsAPlainMember();

        mockMvc.perform(get("/api/v1/project/{id}/employees", PROJECT).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void delete_asAProjectManager_isAllowed() throws Exception {
        callerIsAProjectManager();

        mockMvc.perform(delete("/api/v1/project/{id}", PROJECT).with(jwt()))
                .andExpect(status().isOk());
    }

    /** Membership alone reads; it does not write. */
    @Test
    void delete_asAPlainMember_isForbidden() throws Exception {
        callerIsAPlainMember();

        mockMvc.perform(delete("/api/v1/project/{id}", PROJECT).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(projectService);
    }

    @Test
    void assignEmployee_asAPlainMember_isForbidden() throws Exception {
        callerIsAPlainMember();

        mockMvc.perform(post("/api/v1/project/{p}/employees/{e}", PROJECT, EMPLOYEE).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(projectService);
    }

    @Test
    void assignEmployee_asAProjectManager_isAllowed() throws Exception {
        callerIsAProjectManager();

        mockMvc.perform(post("/api/v1/project/{p}/employees/{e}", PROJECT, EMPLOYEE).with(jwt()))
                .andExpect(status().isOk());
    }

    /** The old authority is not kept as a branch: holding it opens nothing. */
    @Test
    void theOldAuthorityAloneOpensNothing() throws Exception {
        callerIsAnOutsider();

        mockMvc.perform(get("/api/v1/project/{id}", PROJECT)
                        .with(jwt().authorities(new SimpleGrantedAuthority("project:read"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/project/{id}", PROJECT)
                        .with(jwt().authorities(new SimpleGrantedAuthority("project:admin"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(projectService);
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            // A 401 for a request with no token, as the resource server answers in production.
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(handling -> handling.authenticationEntryPoint(
                            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
            return http.build();
        }
    }
}
