package org.tornotron.echno_backend.storageLocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationDto;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice authorization test for StorageLocationControllerWeb, split the same way as
 * MaterialControllerWeb: a system-admin or a project-manager may read the locations, only a
 * system-admin may create, edit or delete one.
 *
 * <p>The read is what the stock-adjustment form picks the counted shelf from, and a project
 * manager may raise and approve those documents. A location list they cannot read leaves the
 * balance lookup they can now reach with nothing to be scoped by.
 *
 * <p>Whether a storekeeper should be able to create a location without also being able to delete
 * a project is the role question in #650, and this does not answer it: the writes are untouched.
 */
@WebMvcTest(StorageLocationControllerWeb.class)
@Import(StorageLocationControllerWebAuthzTest.TestSecurityConfig.class)
class StorageLocationControllerWebAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StorageLocationService storageLocationService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here
    // because .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    // RPTExchangeFilter also depends on this cache; mocked for the same reason.
    @MockitoBean
    private RPTCache rptCache;

    /** A caller holding project-manager and not system-admin. */
    private void asProjectManager() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(true);
    }

    private void stubReads() {
        when(storageLocationService.getAllStorageLocations(anyInt(), anyInt())).thenReturn(Page.empty());
        when(storageLocationService.getStorageLocationsByProject(anyLong())).thenReturn(List.of());
        when(storageLocationService.getStorageLocationsByType(any(StorageLocationType.class)))
                .thenReturn(List.of());
        when(storageLocationService.getStorageLocationById(anyLong())).thenReturn(new StorageLocationDto());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/storage-locations/web",
            "/api/v1/storage-locations/web/all",
            "/api/v1/storage-locations/web/1",
            "/api/v1/storage-locations/web/project/4",
            "/api/v1/storage-locations/web/type/WAREHOUSE"
    })
    void aProjectManagerMayReadTheLocations(String path) throws Exception {
        asProjectManager();
        stubReads();

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void aMemberHoldingNeitherRoleIsStillRefusedTheRead() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(false);

        mockMvc.perform(get("/api/v1/storage-locations/web/project/4").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aProjectManagerMayNotDeleteALocation() throws Exception {
        asProjectManager();

        mockMvc.perform(delete("/api/v1/storage-locations/web/1").with(jwt()).with(csrf()))
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
