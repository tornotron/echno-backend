package org.tornotron.echno_backend.finance.construction.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentStatus;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.pdf.ConstructionInvoicePdfService;
import org.tornotron.echno_backend.finance.construction.service.ConstructionInvoiceService;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test for the invoice PDF download endpoint. It pins that the guard admits a
 * caller the access matrix lets read finance records and forbids one it does not, and
 * that a served PDF carries the application/pdf content type and an attachment filename
 * derived from the invoice number. The rendering itself is stubbed; @access is mocked.
 */
@WebMvcTest(ConstructionInvoiceControllerWeb.class)
@Import(ConstructionInvoicePdfAuthzTest.TestSecurityConfig.class)
class ConstructionInvoicePdfAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConstructionInvoiceService invoiceService;

    @MockitoBean
    private ConstructionInvoicePdfService pdfService;

    @MockitoBean(name = "access")
    private AccessGuard access;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void downloadPdf_isOk_andServesAnAttachment_forACallerWhoMayReadFinance() throws Exception {
        UUID id = UUID.randomUUID();
        when(access.can("finance", "read")).thenReturn(true);
        when(invoiceService.findById(id)).thenReturn(stubInvoice(id));
        when(pdfService.render(any())).thenReturn("%PDF-1.4 stub".getBytes());

        mockMvc.perform(get("/api/v1/finance/construction-invoices/web/{id}/pdf", id).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"CINV-2026-0042.pdf\""));
    }

    @Test
    void downloadPdf_isForbidden_forACallerWhoMayNotReadFinance() throws Exception {
        UUID id = UUID.randomUUID();
        when(access.can("finance", "read")).thenReturn(false);

        mockMvc.perform(get("/api/v1/finance/construction-invoices/web/{id}/pdf", id).with(jwt()))
                .andExpect(status().isForbidden());
    }

    private ConstructionInvoiceDto stubInvoice(UUID id) {
        return new ConstructionInvoiceDto(
                id, "CINV-2026-0042", ConstructionInvoiceType.PURCHASE,
                ConstructionInvoiceStatus.APPROVED, ConstructionPaymentStatus.UNPAID,
                42L, 17L, null, null,
                null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null,
                List.of());
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
