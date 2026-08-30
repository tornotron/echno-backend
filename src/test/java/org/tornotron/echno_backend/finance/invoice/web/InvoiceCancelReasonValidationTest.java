package org.tornotron.echno_backend.finance.invoice.web;

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
import org.tornotron.echno_backend.finance.construction.pdf.ConstructionInvoicePdfService;
import org.tornotron.echno_backend.finance.construction.service.ConstructionInvoiceService;
import org.tornotron.echno_backend.finance.construction.web.ConstructionInvoiceControllerWeb;
import org.tornotron.echno_backend.finance.invoice.service.InvoiceService;
import org.tornotron.echno_backend.finance.ledger.JournalLimits;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins that a cancel reason too long for the reversing entry's description is refused at the
 * edge, as a 400 naming the parameter, rather than reaching the ledger and failing as a column
 * overflow on flush.
 *
 * <p>Both cancel endpoints are covered in one slice because the constraint is the same on both
 * and they differ only in the service behind them. The web app caps the field client side, which
 * keeps a person out of this, but any other caller of the API went straight through.
 */
@WebMvcTest({InvoiceControllerWeb.class, ConstructionInvoiceControllerWeb.class})
@Import(InvoiceCancelReasonValidationTest.TestSecurityConfig.class)
class InvoiceCancelReasonValidationTest {

    private static final String TOO_LONG = "x".repeat(JournalLimits.REVERSAL_REASON_MAX_LENGTH + 1);
    private static final String AT_THE_LIMIT = "x".repeat(JournalLimits.REVERSAL_REASON_MAX_LENGTH);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvoiceService invoiceService;

    @MockitoBean
    private ConstructionInvoiceService constructionInvoiceService;

    @MockitoBean
    private ConstructionInvoicePdfService pdfService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void customerInvoiceCancel_withAnOverLongReason_isRefusedBeforeTheServiceIsCalled() throws Exception {
        allowTheCaller();

        mockMvc.perform(post("/api/v1/finance/invoices/web/{id}/cancel", UUID.randomUUID())
                        .param("reason", TOO_LONG).with(jwt()))
                .andExpect(status().isBadRequest());

        verify(invoiceService, never()).cancel(any(), anyString());
    }

    @Test
    void customerInvoiceCancel_withABlankReason_isRefused() throws Exception {
        allowTheCaller();

        mockMvc.perform(post("/api/v1/finance/invoices/web/{id}/cancel", UUID.randomUUID())
                        .param("reason", "   ").with(jwt()))
                .andExpect(status().isBadRequest());

        verify(invoiceService, never()).cancel(any(), anyString());
    }

    @Test
    void customerInvoiceCancel_withAReasonAtTheLimit_reachesTheService() throws Exception {
        allowTheCaller();

        mockMvc.perform(post("/api/v1/finance/invoices/web/{id}/cancel", UUID.randomUUID())
                        .param("reason", AT_THE_LIMIT).with(jwt()))
                .andExpect(status().isOk());

        verify(invoiceService).cancel(any(), anyString());
    }

    @Test
    void constructionInvoiceCancel_withAnOverLongReason_isRefusedBeforeTheServiceIsCalled() throws Exception {
        allowTheCaller();

        mockMvc.perform(post("/api/v1/finance/construction-invoices/web/{id}/cancel", UUID.randomUUID())
                        .param("reason", TOO_LONG).with(jwt()))
                .andExpect(status().isBadRequest());

        verify(constructionInvoiceService, never()).cancel(any(), anyString());
    }

    @Test
    void constructionInvoiceCancel_withABlankReason_isRefused() throws Exception {
        allowTheCaller();

        mockMvc.perform(post("/api/v1/finance/construction-invoices/web/{id}/cancel", UUID.randomUUID())
                        .param("reason", "").with(jwt()))
                .andExpect(status().isBadRequest());

        verify(constructionInvoiceService, never()).cancel(any(), anyString());
    }

    private void allowTheCaller() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(true);
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
