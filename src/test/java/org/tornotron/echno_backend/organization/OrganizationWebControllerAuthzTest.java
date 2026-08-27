package org.tornotron.echno_backend.organization;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice authorization test for the organization list endpoint, the screen that populates
 * the organization picker.
 *
 * <p>The guard here has to be authentication alone. This endpoint answers "which organizations
 * am I in", so it runs before a tenant has been selected: any check reading TenantContext is
 * circular and fails closed, which is what produced the 403 on the Organizations page. These
 * tests pin that the endpoint is reachable with no tenant set and no elevated role, and that
 * the tenant-scoped role service is not consulted for it at all.
 *
 * <p>The result is narrowed to the caller's own organizations inside OrganizationService, by a
 * query on the authenticated user's identity, so the relaxed guard cannot widen what comes back.
 * OrganizationServiceScopingTest covers that filtering.
 */
@WebMvcTest(OrganizationWebController.class)
@Import(OrganizationWebControllerAuthzTest.TestSecurityConfig.class)
class OrganizationWebControllerAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationService organizationService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references on the other
    // endpoints of this controller.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here
    // because .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    // RPTExchangeFilter also depends on this cache; mocked for the same reason.
    @MockitoBean
    private RPTCache rptCache;

    @BeforeEach
    @AfterEach
    void clearTenant() {
        // The picker is requested with no tenant chosen, which is the state that broke it.
        TenantContext.clear();
    }

    @Test
    void readAllOrganizations_isOk_forAPlainMemberWithNoRoleAndNoTenantSelected() throws Exception {
        // The case failing in production: a member holding no system-admin role, on the picker
        // screen, so TenantContext is empty. Under the old tenant-scoped role guard this was 403.
        OrganizationDto org = new OrganizationDto();
        org.setId(7L);
        org.setOrganizationName("Asset Homes");
        when(organizationService.getAllOrganization()).thenReturn(List.of(org));

        mockMvc.perform(get("/api/v1/organization/web").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].organizationName").value("Asset Homes"));
    }

    @Test
    void readAllOrganizations_doesNotConsultTheTenantScopedRoleService() throws Exception {
        // Guards against a tenant-scoped check being reintroduced here. Anything reading
        // TenantContext is circular on the picker, since the caller is choosing the tenant.
        when(organizationService.getAllOrganization()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/organization/web").with(jwt()))
                .andExpect(status().isOk());

        verifyNoInteractions(orgSecurity);
    }

    @Test
    void readAllOrganizations_returnsAnEmptyListForAMemberOfNothing() throws Exception {
        // Belonging to no organization is a legitimate 200 with nothing in it, not a refusal.
        when(organizationService.getAllOrganization()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/organization/web").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void readAllOrganizations_isRefused_forAnAnonymousCaller() throws Exception {
        // Relaxing the guard to isAuthenticated() must still keep out an unauthenticated caller.
        // The refusal is asserted rather than a specific status: the running application answers
        // 401 through the bearer-token entry point of its resource server, while this slice has
        // no such entry point and falls back to 403. The status is a property of that shared
        // configuration, not of this endpoint's guard, so pinning it here would test the harness.
        mockMvc.perform(get("/api/v1/organization/web"))
                .andExpect(status().is4xxClientError());

        // What this endpoint's guard owns: an anonymous request never reaches the handler.
        verifyNoInteractions(organizationService);
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
