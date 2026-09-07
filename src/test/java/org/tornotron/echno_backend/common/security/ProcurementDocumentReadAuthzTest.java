package org.tornotron.echno_backend.common.security;

import org.junit.jupiter.api.Test;
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
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNoteControllerWeb;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNoteService;
import org.tornotron.echno_backend.indent.IndentControllerWeb;
import org.tornotron.echno_backend.indent.IndentService;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumptionControllerWeb;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumptionService;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderController;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderControllerWeb;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderService;
import org.tornotron.echno_backend.siteTransfer.SiteTransferControllerWeb;
import org.tornotron.echno_backend.siteTransfer.SiteTransferService;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferDto;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A project manager may read the procurement documents behind the movements they already see, and
 * may still not raise one.
 *
 * <p>#672 moved ten reads on {@code InventoryTransactionControllerWeb} to
 * {@code system-admin, project-manager}, so the role that approves a stock adjustment can read the
 * {@code ADJUST} rows the approval wrote. The ledger does not separate movement types, so the same
 * reads return the {@code GRN}, {@code TRANSFER_IN}, {@code TRANSFER_OUT}, {@code USE} and
 * {@code PRODUCTION_CONSUME} rows too, with {@code referenceNumber}, {@code quantityChanged},
 * {@code remarks} and {@code unitCost} on each. Meanwhile every controller for the documents that
 * wrote those rows was {@code system-admin} on all of its methods, reads included. The derived view
 * was open and the document was shut, which is #680.
 *
 * <p>It is settled by opening the documents. A project manager already reads every project, task,
 * employee, material, storage location and stock adjustment, and the whole general ledger. On that
 * evidence these five were the outlier. That also closes #695, where a project manager could raise
 * and approve the stock adjustment that settles a transfer receipt variance while being refused the
 * transfer the adjustment names.
 *
 * <p>Writes do not move, and half of this class is about that. Reading a purchase order is not
 * raising one, receiving a transfer posts stock into a project, and cancelling one takes it back
 * out. Those stay where they were.
 *
 * <p>One slice over six controllers rather than six slices, following
 * {@link ReadWriteGuardSymmetryTest}: Spring caches a context per distinct slice and the test JVM
 * is capped. {@code @orgSecurity} is mocked, and the stubs use exact role arguments, so what is
 * pinned is the role list each guard actually asks for.
 */
@WebMvcTest({
        GoodsReceivedNoteControllerWeb.class,
        PurchaseOrderControllerWeb.class,
        PurchaseOrderController.class,
        IndentControllerWeb.class,
        SiteTransferControllerWeb.class,
        MaterialConsumptionControllerWeb.class
})
@Import(ProcurementDocumentReadAuthzTest.TestSecurityConfig.class)
class ProcurementDocumentReadAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GoodsReceivedNoteService goodsReceivedNoteService;

    @MockitoBean
    private PurchaseOrderService purchaseOrderService;

    @MockitoBean
    private IndentService indentService;

    @MockitoBean
    private SiteTransferService siteTransferService;

    @MockitoBean
    private MaterialConsumptionService materialConsumptionService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here because
    // .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    /** The caller holds project-manager and nothing else, which is the role under test. */
    private void aProjectManager() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager"))
                .thenReturn(true);
    }

    // ---- the reads a project manager gains ----

    @Test
    void aProjectManagerReadsGoodsReceivedNotes() throws Exception {
        aProjectManager();
        when(goodsReceivedNoteService.getAllGrns(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/grns/web").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void aProjectManagerReadsPurchaseOrders() throws Exception {
        aProjectManager();
        when(purchaseOrderService.getAllPurchaseOrders(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/purchase-orders/web").with(jwt()))
                .andExpect(status().isOk());
    }

    /**
     * The mobile twin of the purchase order reads is on the same org-role guard as the web one, so
     * it moves with it. The other four mobile controllers are on flat {@code resource:scope}
     * authorities that nothing mints, refuse every caller, and are a separate repair.
     */
    @Test
    void aProjectManagerReadsPurchaseOrdersOnTheMobileTwin() throws Exception {
        aProjectManager();
        when(purchaseOrderService.getAllPurchaseOrders(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/purchase-orders").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void aProjectManagerReadsIndents() throws Exception {
        aProjectManager();
        when(indentService.getAllIndents(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/indents/web").with(jwt()))
                .andExpect(status().isOk());
    }

    /** The summary listing is the same rows without their lines, so it moves with the listing. */
    @Test
    void aProjectManagerReadsTheIndentSummaryListing() throws Exception {
        aProjectManager();
        when(indentService.getAllIndentsSummary(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/indents/web/summary").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void aProjectManagerReadsSiteTransfers() throws Exception {
        aProjectManager();
        when(siteTransferService.getAllSiteTransfers(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/site-transfers/web").with(jwt()))
                .andExpect(status().isOk());
    }

    /** #695: the transfer a stock adjustment names, which the same role may raise and approve. */
    @Test
    void aProjectManagerOpensTheTransferAStockAdjustmentNames() throws Exception {
        aProjectManager();
        when(siteTransferService.getSiteTransferById(anyLong())).thenReturn(new SiteTransferDto());

        mockMvc.perform(get("/api/v1/site-transfers/web/31").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void aProjectManagerReadsMaterialConsumptions() throws Exception {
        aProjectManager();
        when(materialConsumptionService.getAllMaterialConsumptions(anyInt(), anyInt()))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/material-consumptions/web").with(jwt()))
                .andExpect(status().isOk());
    }

    /**
     * The status trail names who moved a document and when. It goes with the document rather than
     * being held back: the ledger rows a project manager already reads carry {@code createdBy}, so
     * withholding the trail would hide the same fact from the surface where it reads as an answer.
     */
    @Test
    void aProjectManagerReadsASiteTransferStatusTrail() throws Exception {
        aProjectManager();
        when(siteTransferService.getStatusHistory(anyLong(), anyInt(), anyInt()))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/site-transfers/web/31/status-history").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void aProjectManagerReadsAPurchaseOrderStatusTrail() throws Exception {
        aProjectManager();
        when(purchaseOrderService.getStatusHistory(anyLong(), anyInt(), anyInt()))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/purchase-orders/web/31/status-history").with(jwt()))
                .andExpect(status().isOk());
    }

    /**
     * The by-project listings take the project from the caller and no guard reads it, so they are
     * as wide as the unfiltered listing rather than narrower. That is why they move together with
     * it: opening only these would read as project-scoped while not being it. {@code project-manager}
     * is an organization role here and not a per-project one, so this grants nothing the listing
     * above does not already grant.
     */
    @Test
    void aProjectManagerReadsTransfersSentFromAProject() throws Exception {
        aProjectManager();
        when(siteTransferService.getSiteTransfersBySendingProject(anyLong())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/site-transfers/web/sending-project/9").with(jwt()))
                .andExpect(status().isOk());
    }

    // ---- the writes a project manager does not gain ----

    /**
     * The write refusals below use the endpoints whose guard is reached without a request body.
     * {@code @PreAuthorize} is evaluated when the handler is invoked, which is after argument
     * binding, so a deliberately empty body on a validated endpoint answers 400 and proves nothing
     * about the guard. {@link ProcurementDocumentGuardSplitTest} covers every write, body or not,
     * by reading the annotation itself.
     */
    @Test
    void aProjectManagerCannotMoveAPurchaseOrdersStatus() throws Exception {
        aProjectManager();

        mockMvc.perform(patch("/api/v1/purchase-orders/web/31/status").with(jwt()).with(csrf())
                        .param("status", "APPROVED"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aProjectManagerCannotDeleteAnIndent() throws Exception {
        aProjectManager();

        mockMvc.perform(delete("/api/v1/indents/web/31").with(jwt()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aProjectManagerCannotReceiveASiteTransfer() throws Exception {
        aProjectManager();

        mockMvc.perform(post("/api/v1/site-transfers/web/31/receive").with(jwt()).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"items\":[{\"itemId\":84,\"receivedQuantity\":8}]}"))
                .andExpect(status().isForbidden());
    }

    /** Cancelling returns stock to the sending project, so it is gated the same way. */
    @Test
    void aProjectManagerCannotCancelASiteTransfer() throws Exception {
        aProjectManager();

        mockMvc.perform(post("/api/v1/site-transfers/web/31/cancel").with(jwt()).with(csrf())
                        .contentType(APPLICATION_JSON).content("{\"reason\":\"Lorry turned back\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- and a caller holding neither role still reads nothing ----

    @Test
    void aCallerWithNoElevatedRoleStillReadsNoPurchaseOrders() throws Exception {
        mockMvc.perform(get("/api/v1/purchase-orders/web").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCallerWithNoElevatedRoleStillReadsNoSiteTransfers() throws Exception {
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
