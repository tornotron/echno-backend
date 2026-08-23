package org.tornotron.echno_backend.siteTransfer;

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

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice authorization test for SiteTransferControllerWeb. Every endpoint on this
 * controller gates on the single system-admin role, so the reads are more tightly
 * restricted than on the inventory-document controllers (which let any member read).
 * The stubs use exact role arguments: the system-admin test proves admittance, and the
 * project-manager test proves that role alone is refused, which locks in the exact role
 * name the guard requires. @orgSecurity is mocked.
 */
@WebMvcTest(SiteTransferControllerWeb.class)
@Import(SiteTransferControllerWebAuthzTest.TestSecurityConfig.class)
class SiteTransferControllerWebAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SiteTransferService siteTransferService;

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
    void readAll_isOk_forASystemAdmin() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(true);
        when(siteTransferService.getAllSiteTransfers()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/site-transfers/web").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void readAll_isForbidden_forAProjectManagerWhoIsNotASystemAdmin() throws Exception {
        // Only 'project-manager' holds here; the guard demands 'system-admin', so the
        // controller's exact-role check must reject this caller.
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("project-manager")).thenReturn(true);

        mockMvc.perform(get("/api/v1/site-transfers/web").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void readAll_isForbidden_forACallerWithNoElevatedRole() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(false);

        mockMvc.perform(get("/api/v1/site-transfers/web").with(jwt()))
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
