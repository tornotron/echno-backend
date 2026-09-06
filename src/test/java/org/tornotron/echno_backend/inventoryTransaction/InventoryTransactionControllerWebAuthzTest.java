package org.tornotron.echno_backend.inventoryTransaction;

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
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryMaterialStockDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryTransactionDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.MaterialLocationStockDto;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice authorization test for InventoryTransactionControllerWeb, which is read-only
 * throughout and split by what each read is anchored to.
 *
 * <p>A project manager raises and approves stock adjustments, and approving one is what writes
 * the ADJUST rows carrying the reason the stock moved. #666 gave that role the material, the
 * storage location and the balance the document is stamped against; it left the ledger those
 * three describe behind, so the person who signed a correction could not read what the
 * correction did. A read anchored to a material, a storage location, a project or a task now
 * answers a project manager, because all four of those objects already do.
 *
 * <p>The four listings that name nothing and range over the whole organization stay with
 * system-admin. That line is about which screen the ledger belongs to rather than about
 * confidentiality: a caller who may list every material may walk the anchored reads and
 * reassemble the same rows. It is drawn so that the movement report stays an admin surface
 * until somebody decides it should not, which is the question #650 asks of the whole Resources
 * domain.
 */
@WebMvcTest(InventoryTransactionControllerWeb.class)
@Import(InventoryTransactionControllerWebAuthzTest.TestSecurityConfig.class)
class InventoryTransactionControllerWebAuthzTest {

    /** Reads that name a material, a storage location, a project or a task. */
    private static final String[] ANCHORED_READS = {
            "/api/v1/inventory-transactions/web/1",
            "/api/v1/inventory-transactions/web/material/7",
            "/api/v1/inventory-transactions/web/material/7/history",
            "/api/v1/inventory-transactions/web/material/7/stock",
            "/api/v1/inventory-transactions/web/project/4",
            "/api/v1/inventory-transactions/web/project/4/task-summary",
            "/api/v1/inventory-transactions/web/storage-location/3",
            "/api/v1/inventory-transactions/web/storage-location/3/stock",
            "/api/v1/inventory-transactions/web/storage-location/3/material/7/project/4",
            "/api/v1/inventory-transactions/web/task/9"
    };

    /** Reads that name nothing and range over the whole organization. */
    private static final String[] ORGANIZATION_WIDE_READS = {
            "/api/v1/inventory-transactions/web",
            "/api/v1/inventory-transactions/web/all",
            "/api/v1/inventory-transactions/web/type/ADJUST",
            "/api/v1/inventory-transactions/web/date-range"
                    + "?startDate=2026-01-01T00:00:00&endDate=2026-02-01T00:00:00"
    };

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryTransactionService inventoryTransactionService;

    @MockitoBean
    private InventoryService inventoryService;

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

    /** A caller holding system-admin, which satisfies both spellings of the guard. */
    private void asSystemAdmin() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(true);
    }

    private void stubReads() {
        when(inventoryTransactionService.getTransactionById(anyLong()))
                .thenReturn(new InventoryTransactionDto());
        when(inventoryTransactionService.getAllTransactions(anyInt(), anyInt())).thenReturn(Page.empty());
        when(inventoryTransactionService.getTransactionsByMaterial(anyLong())).thenReturn(List.of());
        when(inventoryTransactionService.getMaterialMovementHistory(anyLong(), anyInt(), anyInt()))
                .thenReturn(Page.empty());
        when(inventoryTransactionService.getTransactionsByProject(anyLong())).thenReturn(List.of());
        when(inventoryTransactionService.getTransactionsByType(any(InventoryTransactionType.class)))
                .thenReturn(List.of());
        when(inventoryTransactionService.getTransactionsByDateRange(
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of());
        when(inventoryTransactionService.getTransactionsByStorageLocation(anyLong())).thenReturn(List.of());
        when(inventoryTransactionService.getTransactionsByStorageLocationMaterialAndProject(
                anyLong(), anyLong(), anyLong())).thenReturn(List.of());
        when(inventoryTransactionService.getTransactionsByTask(anyLong())).thenReturn(List.of());
        when(inventoryTransactionService.getTaskMaterialUsageSummary(anyLong())).thenReturn(List.of());
        when(inventoryService.getStockByStorageLocation(anyLong()))
                .thenReturn(new InventoryMaterialStockDto());
        when(inventoryService.getStockByMaterial(anyLong())).thenReturn(new MaterialLocationStockDto());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/inventory-transactions/web/1",
            "/api/v1/inventory-transactions/web/material/7",
            "/api/v1/inventory-transactions/web/material/7/history",
            "/api/v1/inventory-transactions/web/material/7/stock",
            "/api/v1/inventory-transactions/web/project/4",
            "/api/v1/inventory-transactions/web/project/4/task-summary",
            "/api/v1/inventory-transactions/web/storage-location/3",
            "/api/v1/inventory-transactions/web/storage-location/3/stock",
            "/api/v1/inventory-transactions/web/storage-location/3/material/7/project/4",
            "/api/v1/inventory-transactions/web/task/9"
    })
    void aProjectManagerMayReadTheLedgerBehindAThingTheyCanOpen(String path) throws Exception {
        asProjectManager();
        stubReads();

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/inventory-transactions/web",
            "/api/v1/inventory-transactions/web/all",
            "/api/v1/inventory-transactions/web/type/ADJUST",
            "/api/v1/inventory-transactions/web/date-range"
                    + "?startDate=2026-01-01T00:00:00&endDate=2026-02-01T00:00:00"
    })
    void aProjectManagerIsStillRefusedTheOrganizationWideListings(String path) throws Exception {
        asProjectManager();
        stubReads();

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isForbidden());
    }

    /**
     * The two lists above must not overlap and must together name every mapping on the
     * controller. Without this the split could be asserted twice over nine paths and leave the
     * tenth ungoverned by either expectation, which is the failure a parameterized pair of
     * lists invites.
     */
    @Test
    void everyMappingOnTheControllerIsClaimedByExactlyOneOfTheTwoLists() {
        long mappings = java.util.Arrays.stream(InventoryTransactionControllerWeb.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class))
                .count();

        org.assertj.core.api.Assertions.assertThat(mappings)
                .as("handler methods on InventoryTransactionControllerWeb")
                .isEqualTo(ANCHORED_READS.length + ORGANIZATION_WIDE_READS.length);

        org.assertj.core.api.Assertions.assertThat(ANCHORED_READS)
                .doesNotContainAnyElementsOf(java.util.List.of(ORGANIZATION_WIDE_READS));
    }

    @Test
    void aSystemAdminStillReadsTheWholeLedger() throws Exception {
        asSystemAdmin();
        stubReads();

        for (String path : ANCHORED_READS) {
            mockMvc.perform(get(path).with(jwt())).andExpect(status().isOk());
        }
        for (String path : ORGANIZATION_WIDE_READS) {
            mockMvc.perform(get(path).with(jwt())).andExpect(status().isOk());
        }
    }

    @Test
    void aMemberHoldingNeitherRoleIsStillRefusedTheAnchoredRead() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(false);

        mockMvc.perform(get("/api/v1/inventory-transactions/web/material/7/history").with(jwt()))
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
