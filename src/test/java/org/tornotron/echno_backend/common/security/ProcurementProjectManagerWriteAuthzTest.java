package org.tornotron.echno_backend.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.indent.IndentControllerWeb;
import org.tornotron.echno_backend.indent.IndentService;
import org.tornotron.echno_backend.indentItem.IndentItemControllerWeb;
import org.tornotron.echno_backend.indentItem.IndentItemService;
import org.tornotron.echno_backend.material.MaterialControllerWeb;
import org.tornotron.echno_backend.material.MaterialService;
import org.tornotron.echno_backend.material.lowstock.LowStockService;
import org.tornotron.echno_backend.material.summary.MaterialStockSummaryService;
import org.tornotron.echno_backend.material.threshold.MaterialLocationThresholdService;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderController;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderControllerWeb;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderService;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemController;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemControllerWeb;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemService;

import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Project managers manage purchases (#855): they create and edit materials, indents and purchase
 * orders, on every twin that has a working org-role guard, and nothing past that moves.
 *
 * <p>The product owner's answer on #855 was to give project managers the purchasing writes by
 * default. What stays with {@code system-admin} is listed here endpoint by endpoint: deleting a
 * material, an indent line or a purchase order line, removing a threshold override, and every
 * purchase order status change, approval included. Receipts and stock posting live on other
 * controllers and are not touched. The two service-level routes around the approval (a status in
 * the header payload, and editing an order after it was approved) are pinned in
 * {@code PurchaseOrderProjectManagerEditTest}.
 *
 * <p>The mobile material and indent twins still carry {@code resource:scope} guards that the realm
 * cannot satisfy, for any role; aligning them with these guards is #1002, so they are not here.
 *
 * <p>One slice over seven controllers, as in {@link ProcurementDocumentReadAuthzTest}, because
 * Spring caches a context per distinct slice. {@code @orgSecurity} is mocked with exact role
 * arguments, so what is pinned is the role list each guard asks for. The services are mocks that
 * answer null, which the controllers wrap without looking at, so a write that is let through answers
 * 2xx and a refused one 403.
 */
@WebMvcTest({
        MaterialControllerWeb.class,
        IndentControllerWeb.class,
        IndentItemControllerWeb.class,
        PurchaseOrderControllerWeb.class,
        PurchaseOrderController.class,
        PurchaseOrderItemControllerWeb.class,
        PurchaseOrderItemController.class
})
@Import(ProcurementProjectManagerWriteAuthzTest.TestSecurityConfig.class)
class ProcurementProjectManagerWriteAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private MaterialService materialService;
    @MockitoBean private MaterialLocationThresholdService thresholdService;
    @MockitoBean private LowStockService lowStockService;
    @MockitoBean private MaterialStockSummaryService stockSummaryService;
    @MockitoBean private IndentService indentService;
    @MockitoBean private IndentItemService indentItemService;
    @MockitoBean private PurchaseOrderService purchaseOrderService;
    @MockitoBean private PurchaseOrderItemService purchaseOrderItemService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfy RPTExchangeFilter, a custom filter the web slice loads; unused because
    // .with(jwt()) sets the authentication directly.
    @MockitoBean private KeycloakAuthorizationService keycloakAuthorizationService;
    @MockitoBean private RPTCache rptCache;

    /**
     * Holds project-manager and not system-admin. The pair is what the purchasing writes ask for;
     * the stores tier is what the reads ask for. {@code "system-admin"} alone is left unstubbed,
     * so it answers false, which is what makes the refusals below the guard's own.
     */
    private void asProjectManager() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "store-keeper", "project-manager"))
                .thenReturn(true);
    }

    private MockHttpServletRequestBuilder call(String method, String path, String body) {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), path)
                .with(jwt()).with(csrf());
        if (body != null && !body.isBlank()) {
            builder.contentType(APPLICATION_JSON).content(body);
        }
        return builder;
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            // Materials: the catalogue entries a purchase names.
            "POST  | /api/v1/materials/web | `{\"materialName\":\"OPC 53 Grade Cement\",\"unit\":\"bags\",\"createdBy\":5}`",
            "PATCH | /api/v1/materials/web/7 | `{\"materialName\":\"OPC 53 Grade Cement\"}`",
            // Indents, and their lines through both routes the web uses.
            "POST  | /api/v1/indents/web | `{\"projectId\":4,\"createdByEmployeeId\":5,\"status\":\"PENDING\"}`",
            "PATCH | /api/v1/indents/web/31 | `{\"remarks\":\"Needed before the slab pour\"}`",
            "POST  | /api/v1/indents/web/31/items | `{\"materialId\":7,\"requestedQuantity\":40}`",
            "PATCH | /api/v1/indents/web/31/items/84 | `{\"requestedQuantity\":45}`",
            "POST  | /api/v1/indent-items/web | `{\"indentId\":31,\"materialId\":7,\"requestedQuantity\":40}`",
            "PUT   | /api/v1/indent-items/web/84 | `{\"materialId\":7,\"requestedQuantity\":45}`",
            "PUT   | /api/v1/indent-items/web/84/mark-converted?purchaseOrderNumber=PO-0042 | ``",
            // Purchase orders and their lines, web and mobile twins.
            "POST  | /api/v1/purchase-orders/web | `{\"vendorId\":3,\"projectId\":4,\"createdBy\":5}`",
            "POST  | /api/v1/purchase-orders | `{\"vendorId\":3,\"projectId\":4,\"createdBy\":5}`",
            "PATCH | /api/v1/purchase-orders/web | `{\"id\":31,\"remarks\":\"Deliver to the north gate\"}`",
            "PATCH | /api/v1/purchase-orders | `{\"id\":31,\"remarks\":\"Deliver to the north gate\"}`",
            "POST  | /api/v1/purchase-order-items/web | `{\"purchaseOrderId\":31,\"materialId\":7,\"orderedQuantity\":40,\"unitPrice\":385.00}`",
            "POST  | /api/v1/purchase-order-items | `{\"purchaseOrderId\":31,\"materialId\":7,\"orderedQuantity\":40,\"unitPrice\":385.00}`",
            "PATCH | /api/v1/purchase-order-items/web | `{\"id\":84,\"orderedQuantity\":45}`",
            "PUT   | /api/v1/purchase-order-items | `{\"id\":84,\"orderedQuantity\":45}`",
            // The line reads the edit screens make, which the role did not have before.
            "GET   | /api/v1/indent-items/web/indent/31 | ``",
            "GET   | /api/v1/purchase-order-items/web/purchase-order/31 | ``",
            "GET   | /api/v1/purchase-order-items/purchase-order/31 | ``"
    })
    void aProjectManagerManagesPurchases(String method, String path, String body) throws Exception {
        asProjectManager();

        mockMvc.perform(call(method.trim(), path.trim(), body))
                .andExpect(status().is2xxSuccessful());
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            // Deletions stay with the administrator.
            "DELETE | /api/v1/materials/web/7",
            "DELETE | /api/v1/materials/web/7/location-thresholds/2",
            "DELETE | /api/v1/indents/web/31",
            "DELETE | /api/v1/indents/web/31/items/84",
            "DELETE | /api/v1/indent-items/web/84",
            "DELETE | /api/v1/purchase-order-items/web/84",
            "DELETE | /api/v1/purchase-order-items/84",
            // So does every status change, approval included.
            "PATCH  | /api/v1/purchase-orders/web/31/status?status=APPROVED",
            "PATCH  | /api/v1/purchase-orders/31/status?status=APPROVED",
            "PATCH  | /api/v1/purchase-orders/web/31/status?status=SENT_TO_VENDOR",
            "PATCH  | /api/v1/purchase-orders/web/31/status?status=CANCELLED"
    })
    void aProjectManagerStillCannot(String method, String path) throws Exception {
        asProjectManager();

        mockMvc.perform(call(method.trim(), path.trim(), null))
                .andExpect(status().isForbidden());
    }

    /** Opening the writes to project managers is not opening them to every member. */
    @Test
    void aMemberHoldingNeitherRoleStillCannotRaiseAPurchaseOrder() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);

        mockMvc.perform(call("POST", "/api/v1/purchase-orders/web", "{\"vendorId\":3,\"projectId\":4,\"createdBy\":5}"))
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
