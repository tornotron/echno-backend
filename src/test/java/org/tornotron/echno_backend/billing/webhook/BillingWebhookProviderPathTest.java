package org.tornotron.echno_backend.billing.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tornotron.echno_backend.common.exception.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The provider segment of the webhook path. The security layer opens the whole
 * {@code /billing/webhooks/**} prefix, so a provider nobody implements reached the
 * static-resource handler and came back as a 500 (#820). It is a 404 that names no internals,
 * and the Razorpay route still wins over the catch-all template.
 *
 * <p>Standalone MockMvc with the real advice: the assertion is about routing and the problem
 * body, and a web slice would cost the test JVM another cached context to learn nothing more.
 */
@ExtendWith(MockitoExtension.class)
class BillingWebhookProviderPathTest {

    @Mock
    private BillingWebhookService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BillingWebhookController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void anUnknownProviderIsA404NotA500() throws Exception {
        mockMvc.perform(post("/api/v1/billing/webhooks/other").content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("No billing webhook provider 'other'"));
        verifyNoInteractions(service);
    }

    @Test
    void theRazorpayRouteStillWinsOverTheCatchAll() throws Exception {
        when(service.ingest(any(byte[].class), isNull(), isNull())).thenReturn(WebhookIngestResult.ACCEPTED);

        mockMvc.perform(post("/api/v1/billing/webhooks/razorpay").content("{}"))
                .andExpect(status().isOk());
    }
}
