package org.tornotron.echno_backend.modules.access.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;

/**
 * The Roles &amp; Permissions endpoints: who may read them, and the shape the screen depends on.
 *
 * <p>{@code @access} is mocked, so each test states plainly which permission the caller holds.
 */
@WebMvcTest(AccessAdminControllerWeb.class)
@Import(AccessAdminControllerWebTest.TestSecurityConfig.class)
class AccessAdminControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "access")
    private AccessGuard access;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void theCatalogListsSectionsAreasActionsAndScopes() throws Exception {
        when(access.can("access", "view")).thenReturn(true);

        mockMvc.perform(get("/api/v1/access/web/catalog").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(12))
                .andExpect(jsonPath("$.sections[0]").value("Projects"))
                .andExpect(jsonPath("$.areas[?(@.id == 'progress-bills')].section").value("Bills"))
                .andExpect(jsonPath("$.areas[?(@.id == 'progress-bills')].actions[*]")
                        .value(hasItems("view", "approve", "certify")))
                .andExpect(jsonPath("$.areas[?(@.id == 'wbs')].label").value("WBS & Schedule"))
                .andExpect(jsonPath("$.scopes[*]")
                        .value(contains("ALL", "ASSIGNED_PROJECTS", "RELATED", "OWN")));
    }

    @Test
    void theCatalogIsRefusedWithoutAccessView() throws Exception {
        when(access.can("access", "view")).thenReturn(false);

        mockMvc.perform(get("/api/v1/access/web/catalog").with(jwt()))
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
