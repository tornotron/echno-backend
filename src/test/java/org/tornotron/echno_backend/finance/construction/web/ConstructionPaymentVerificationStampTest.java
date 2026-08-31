package org.tornotron.echno_backend.finance.construction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionPaymentRequest;
import org.tornotron.echno_backend.finance.construction.dtos.UpdateConstructionPaymentRequest;
import org.tornotron.echno_backend.finance.construction.service.ConstructionPaymentService;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins that the verification stamp cannot be written from a request body.
 *
 * <p>The bug this covers was not that the stamp was validated loosely, it was that it was bound at
 * all: {@code verifiedBy} and {@code verifiedAt} were fields on the create and update payloads and
 * were copied onto the voucher as sent, so any caller who could edit a voucher could record that a
 * named colleague had checked a payment, at a time of their choosing. The test therefore posts the
 * two fields on the wire, the way the defect was reachable, and asserts that nothing the controller
 * hands the service carries them. Reading the bound request as JSON rather than calling an accessor
 * is deliberate: an accessor would only compile against the payload that no longer has the field,
 * so the test would pass by not existing rather than by the fix.
 *
 * <p>The counterpart to {@code ConstructionPaymentVerifyActionTest}, which covers the action that
 * replaced the fields.
 */
@WebMvcTest(ConstructionPaymentControllerWeb.class)
@Import(ConstructionPaymentVerificationStampTest.TestSecurityConfig.class)
class ConstructionPaymentVerificationStampTest {

    private static final String CREATE_BODY = """
            {
              "type": "INVOICE",
              "method": "BANK_TRANSFER",
              "projectId": 42,
              "amount": 15000.50,
              "paymentDate": "2026-08-05",
              "verifiedBy": 999,
              "verifiedAt": "2020-01-01T00:00:00Z"
            }
            """;

    private static final String UPDATE_BODY = """
            {
              "type": "INVOICE",
              "status": "COMPLETED",
              "method": "BANK_TRANSFER",
              "projectId": 42,
              "amount": 15000.50,
              "paymentDate": "2026-08-05",
              "verifiedBy": 999,
              "verifiedAt": "2020-01-01T00:00:00Z"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ConstructionPaymentService service;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void aCreateBodyNamingAVerifier_reachesTheServiceCarryingNoVerificationStamp() throws Exception {
        allowElevatedRole();

        mockMvc.perform(post("/api/v1/finance/construction-payments/web")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateConstructionPaymentRequest> bound = ArgumentCaptor.captor();
        verify(service).create(bound.capture());
        assertThat(fieldsOf(bound.getValue()))
                .doesNotContainKeys("verifiedBy", "verifiedAt")
                .containsKeys("projectId", "amount", "paymentDate");
    }

    @Test
    void anUpdateBodyNamingAVerifier_reachesTheServiceCarryingNoVerificationStamp() throws Exception {
        allowElevatedRole();
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/finance/construction-payments/web/{id}", id)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateConstructionPaymentRequest> bound = ArgumentCaptor.captor();
        verify(service).update(eq(id), bound.capture());
        assertThat(fieldsOf(bound.getValue()))
                .doesNotContainKeys("verifiedBy", "verifiedAt")
                .containsEntry("status", "COMPLETED");
    }

    @Test
    void verify_isTheActionThatWritesTheStamp_andIsRoleGated() throws Exception {
        UUID id = UUID.randomUUID();

        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager"))
                .thenReturn(false);
        mockMvc.perform(post("/api/v1/finance/construction-payments/web/{id}/verify", id).with(jwt()))
                .andExpect(status().isForbidden());

        allowElevatedRole();
        mockMvc.perform(post("/api/v1/finance/construction-payments/web/{id}/verify", id).with(jwt()))
                .andExpect(status().isOk());
        verify(service).verify(id);
    }

    private void allowElevatedRole() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager"))
                .thenReturn(true);
    }

    /** The request as it reached the service, read as plain JSON so no accessor is named. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fieldsOf(Object boundRequest) {
        return objectMapper.convertValue(boundRequest, Map.class);
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
