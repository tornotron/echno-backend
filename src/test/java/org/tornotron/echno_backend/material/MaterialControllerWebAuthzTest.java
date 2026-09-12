package org.tornotron.echno_backend.material;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.material.threshold.dto.MaterialLocationThresholdDto;
import org.tornotron.echno_backend.material.dto.MaterialStockSummaryDto;
import org.tornotron.echno_backend.material.dto.MaterialWithStockDto;
import org.tornotron.echno_backend.material.lowstock.LowStockService;
import org.tornotron.echno_backend.material.summary.MaterialStockSummaryService;
import org.tornotron.echno_backend.material.threshold.MaterialLocationThresholdService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice authorization test for MaterialControllerWeb, which splits its guard: the catalogue
 * and the balances held against it are readable by a system-admin or a project-manager, and only
 * a system-admin may change any of it.
 *
 * <p>The read half exists because of the workflow, not because material data is uninteresting.
 * A project manager may raise, approve and reject a stock adjustment, and a stock adjustment is a
 * document about a material's balance: since the approval is checked against the balance the
 * document was stamped with, a raiser who cannot read that balance is being asked to correct a
 * figure they are not allowed to see, and cannot be told why an approval was refused. The material
 * list and search are on the same footing, because the form has to name the material before it can
 * ask for its balance.
 *
 * <p>The reads are also what the storekeeper role introduced for #650 reads: a receipt, an issue
 * and a count each name a material before anything else can be typed, so the store cannot be given
 * the write while the catalogue lookup behind the form stays shut.
 *
 * <p>The write half is the ratchet. Creating a material, editing it, or deleting it are catalogue
 * decisions rather than count decisions, and #650 left them with system-admin: deciding what
 * materials exist is a different job from recording what arrived. If a later change widened them
 * along with the reads, {@code aProjectManagerMayNot*} fails.
 *
 * <p>Reorder thresholds at a storage location are the one exception, decided on #650 and built for
 * #746: setting one is open to project-manager and store-keeper as well, because tuning a threshold
 * per project is an operational decision and the low-stock sweep that reads it notifies the same
 * people. Removing an override stays with system-admin, so the pair below pins both directions.
 *
 * <p>{@code @orgSecurity} is mocked so each branch is exercised on its own: the project-manager
 * tests deliberately answer false to the system-admin-only expression and true to the pair, which
 * is exactly a caller holding project-manager and not system-admin.
 */
@WebMvcTest(MaterialControllerWeb.class)
@Import(MaterialControllerWebAuthzTest.TestSecurityConfig.class)
class MaterialControllerWebAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MaterialService materialService;

    @MockitoBean
    private MaterialLocationThresholdService thresholdService;

    @MockitoBean
    private LowStockService lowStockService;

    @MockitoBean
    private MaterialStockSummaryService stockSummaryService;

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
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager", "store-keeper")).thenReturn(true);
    }

    /**
     * A caller holding store-keeper and not system-admin. The mock answers by expression, so at
     * this level a storekeeper and a project manager are the same caller; the source-level
     * {@code StoreKeeperRoleScopeTest} is what tells the two roles apart.
     */
    private void asStoreKeeper() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager", "store-keeper")).thenReturn(true);
    }

    private void stubReads() {
        when(materialService.getAllMaterials(anyInt(), anyInt())).thenReturn(Page.empty());
        when(materialService.searchMaterialsByName(anyString())).thenReturn(List.of());
        when(lowStockService.findLowStock(any(), any(), any())).thenReturn(Page.empty());
        when(thresholdService.listForMaterial(anyLong())).thenReturn(List.of());
        when(materialService.getMaterialStockAtLocation(anyLong(), anyLong(), anyLong()))
                .thenReturn(new MaterialWithStockDto());
        when(materialService.getMaterialWithCurrentStock(anyLong(), anyLong()))
                .thenReturn(new MaterialWithStockDto());
        when(materialService.getMaterialWithAggregateStock(anyLong()))
                .thenReturn(new MaterialWithStockDto());
        when(stockSummaryService.summarize(any())).thenReturn(new MaterialStockSummaryDto());
    }

    /**
     * Every read on the controller, including the balance lookup the stock-adjustment form is
     * built on. Before the fix each of these answered 403 to a project manager.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/materials/web",
            "/api/v1/materials/web/all",
            "/api/v1/materials/web/1",
            "/api/v1/materials/web/search?name=Cement",
            "/api/v1/materials/web/low-stock",
            "/api/v1/materials/web/summary",
            "/api/v1/materials/web/summary?projectId=4",
            "/api/v1/materials/web/1/location-thresholds",
            "/api/v1/materials/web/1/stock",
            "/api/v1/materials/web/1/stock?projectId=4",
            "/api/v1/materials/web/1/stock?projectId=4&storageLocationId=7"
    })
    void aProjectManagerMayReadTheCatalogueAndItsBalances(String path) throws Exception {
        asProjectManager();
        stubReads();

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/materials/web",
            "/api/v1/materials/web/summary",
            "/api/v1/materials/web/1/stock?projectId=4&storageLocationId=7"
    })
    void aMemberHoldingNeitherRoleIsStillRefusedTheRead(String path) throws Exception {
        // Widening the read to project-manager and store-keeper is not widening it to everybody.
        // The tier below both of those is still plain membership, and it reads nothing here.
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager", "store-keeper")).thenReturn(false);

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aProjectManagerMayNotCreateAMaterial() throws Exception {
        asProjectManager();

        mockMvc.perform(post("/api/v1/materials/web").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialName\":\"OPC 53 Grade Cement\",\"unit\":\"bags\",\"createdBy\":5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aProjectManagerMayNotEditAMaterial() throws Exception {
        asProjectManager();

        mockMvc.perform(patch("/api/v1/materials/web/1").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialName\":\"OPC 53 Grade Cement\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aProjectManagerMayNotDeleteAMaterial() throws Exception {
        asProjectManager();

        mockMvc.perform(delete("/api/v1/materials/web/1").with(jwt()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    private static final String THRESHOLD_BODY = "{\"minStock\":10,\"reorderLevel\":20}";

    @Test
    void aProjectManagerMaySetAReorderThreshold() throws Exception {
        // Before #746 this answered 403: the write sat on the system-admin-only expression.
        asProjectManager();
        when(thresholdService.upsert(anyLong(), anyLong(), any())).thenReturn(new MaterialLocationThresholdDto());

        mockMvc.perform(put("/api/v1/materials/web/1/location-thresholds/7").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(THRESHOLD_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void aStoreKeeperMaySetAReorderThreshold() throws Exception {
        asStoreKeeper();
        when(thresholdService.upsert(anyLong(), anyLong(), any())).thenReturn(new MaterialLocationThresholdDto());

        mockMvc.perform(put("/api/v1/materials/web/1/location-thresholds/7").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(THRESHOLD_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void aMemberHoldingNeitherRoleMayNotSetAReorderThreshold() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager", "store-keeper")).thenReturn(false);

        mockMvc.perform(put("/api/v1/materials/web/1/location-thresholds/7").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(THRESHOLD_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void aProjectManagerMayNotRemoveAReorderThreshold() throws Exception {
        // Setting a threshold tunes a project; removing the override puts the catalogue default
        // back, and that stays a catalogue decision.
        asProjectManager();

        mockMvc.perform(delete("/api/v1/materials/web/1/location-thresholds/7").with(jwt()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aStoreKeeperMayNotRemoveAReorderThreshold() throws Exception {
        asStoreKeeper();

        mockMvc.perform(delete("/api/v1/materials/web/1/location-thresholds/7").with(jwt()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSystemAdminStillReadsTheBalance() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager", "store-keeper")).thenReturn(true);
        stubReads();

        mockMvc.perform(get("/api/v1/materials/web/1/stock?projectId=4&storageLocationId=7").with(jwt()))
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
