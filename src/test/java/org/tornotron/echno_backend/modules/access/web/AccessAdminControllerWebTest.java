package org.tornotron.echno_backend.modules.access.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;
import org.tornotron.echno_backend.modules.access.guard.CurrentAccessSubject;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalogReader;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserDto;

/**
 * Every read of the Roles &amp; Permissions screen needs {@code access:read}. {@code @access} is the real guard over a mocked subject and
 * service.
 */
@WebMvcTest(AccessAdminControllerWeb.class)
@Import({AccessAdminControllerWebTest.TestSecurityConfig.class, AccessGuard.class})
class AccessAdminControllerWebTest {

    private static final AccessSubject ADMIN = new AccessSubject(7L, 1L, 11L);
    private static final AccessPermission READ = AccessPermission.of("access:read");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentAccessSubject current;

    @MockitoBean
    private AccessService access;

    @MockitoBean
    private AccessAdminReader reader;

    @MockitoBean
    private AccessCatalogReader catalog;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    private void caller(boolean mayRead) {
        when(current.resolve()).thenReturn(Optional.of(ADMIN));
        when(access.check(eq(ADMIN), eq(READ))).thenReturn(mayRead);
    }

    @Test
    void everyReadIsRefusedWithoutAccessRead() throws Exception {
        caller(false);

        for (String path : List.of("/catalog", "/roles", "/roles/" + UUID.randomUUID(), "/users", "/users/5")) {
            mockMvc.perform(get("/api/v1/access/web" + path).with(jwt())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(reader, catalog);
    }

    @Test
    void theCatalogCarriesTheSectionsAreasAndScopes() throws Exception {
        caller(true);
        when(catalog.areas()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/access/web/catalog").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0]").value("Projects"))
                .andExpect(jsonPath("$.areas").isArray())
                .andExpect(jsonPath("$.scopes[0]").value("ALL"))
                .andExpect(jsonPath("$.scopes.length()").value(4));
    }

    @Test
    void aPersonIsReadInTheCallersOrganization() throws Exception {
        caller(true);
        when(reader.user(any(), eq(5L))).thenReturn(new AccessUserDto(5L, "Asha", "asha@example.test", null, true,
                List.of(), List.of()));

        mockMvc.perform(get("/api/v1/access/web/users/5").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(5))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void aPersonOfAnotherOrganizationIsNotFound() throws Exception {
        caller(true);
        when(reader.user(any(), eq(6L))).thenThrow(new ResourceNotFoundException("not here"));

        mockMvc.perform(get("/api/v1/access/web/users/6").with(jwt())).andExpect(status().isNotFound());
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
