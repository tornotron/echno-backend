package org.tornotron.echno_backend.modules.access.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGroupDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleDto;

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
    private AccessAdminReader reader;

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

    @Test
    void rolesAreListedForTheRequestsOrganization() throws Exception {
        when(access.can("access", "view")).thenReturn(true);
        TenantContext.setCurrentOrgId(7L);
        UUID member = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        when(reader.roles(7L)).thenReturn(List.of(new AccessRoleDto(member, "member", "Member", null,
                "Everyone", true, true, List.of(new AccessGrantDto("leave:create", "OWN", true)),
                List.of(), List.of())));

        mockMvc.perform(get("/api/v1/access/web/roles").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("member"))
                .andExpect(jsonPath("$[0].baseline").value(true))
                .andExpect(jsonPath("$[0].grants[0].permission").value("leave:create"))
                .andExpect(jsonPath("$[0].grants[0].scope").value("OWN"))
                .andExpect(jsonPath("$[0].grants[0].locked").value(true));
    }

    @Test
    void rolesAreRefusedWithoutAccessView() throws Exception {
        when(access.can("access", "view")).thenReturn(false);

        mockMvc.perform(get("/api/v1/access/web/roles").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void groupsAreListedForTheRequestsOrganization() throws Exception {
        when(access.can("access", "view")).thenReturn(true);
        TenantContext.setCurrentOrgId(7L);
        UUID finance = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        when(reader.groups(7L)).thenReturn(List.of(new AccessGroupDto(finance, "DEPARTMENT", "Finance", null,
                List.of(3L), List.of(), List.of(new AccessGrantDto("finance:view", "ALL", false)))));

        mockMvc.perform(get("/api/v1/access/web/groups").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("DEPARTMENT"))
                .andExpect(jsonPath("$[0].memberIds[0]").value(3))
                .andExpect(jsonPath("$[0].grants[0].permission").value("finance:view"));
    }

    @Test
    void groupsAreRefusedWithoutAccessView() throws Exception {
        when(access.can("access", "view")).thenReturn(false);

        mockMvc.perform(get("/api/v1/access/web/groups").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
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
