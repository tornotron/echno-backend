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
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;
import org.tornotron.echno_backend.material.MaterialControllerWeb;
import org.tornotron.echno_backend.material.MaterialService;
import org.tornotron.echno_backend.material.lowstock.LowStockService;
import org.tornotron.echno_backend.material.summary.MaterialStockSummaryService;
import org.tornotron.echno_backend.material.threshold.MaterialLocationThresholdService;

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
 * <p>The indent and purchase order controllers moved onto the access matrix, where
 * {@code IndentAccessEquivalenceIT} and {@code PurchaseOrderAccessEquivalenceIT} hold every role to
 * the access it had, so only the material catalogue is left in this slice.
 *
 * <p>One slice, as in {@link ProcurementDocumentReadAuthzTest}, because
 * Spring caches a context per distinct slice. {@code @access} is mocked with exact
 * actions, so what is pinned is the action each guard asks for. The services are mocks that
 * answer null, which the controllers wrap without looking at, so a write that is let through answers
 * 2xx and a refused one 403.
 */
@WebMvcTest({
        MaterialControllerWeb.class
})
@Import(ProcurementProjectManagerWriteAuthzTest.TestSecurityConfig.class)
class ProcurementProjectManagerWriteAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private MaterialService materialService;
    @MockitoBean private MaterialLocationThresholdService thresholdService;
    @MockitoBean private LowStockService lowStockService;
    @MockitoBean private MaterialStockSummaryService stockSummaryService;

    // Named to match the @access bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "access")
    private AccessGuard access;

    /** Stubs {@code @access} to allow exactly these area:action keys; every other check answers false. */
    private void holds(String... permissions) {
        for (String permission : permissions) {
            String[] parts = permission.split(":");
            when(access.can(parts[0], parts[1])).thenReturn(true);
        }
    }

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
        holds("materials:read", "materials:create", "materials:edit", "materials:set-thresholds");
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
            "PATCH | /api/v1/materials/web/7 | `{\"materialName\":\"OPC 53 Grade Cement\"}`"
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
            "DELETE | /api/v1/materials/web/7/location-thresholds/2"
    })
    void aProjectManagerStillCannot(String method, String path) throws Exception {
        asProjectManager();

        mockMvc.perform(call(method.trim(), path.trim(), null))
                .andExpect(status().isForbidden());
    }

    /** Opening the writes to project managers is not opening them to every member. */
    @Test
    void aMemberHoldingNeitherRoleStillCannotAddAMaterial() throws Exception {
        holds();

        mockMvc.perform(call("POST", "/api/v1/materials/web", "{\"materialName\":\"OPC 53 Grade Cement\",\"unit\":\"bags\",\"createdBy\":5}"))
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
