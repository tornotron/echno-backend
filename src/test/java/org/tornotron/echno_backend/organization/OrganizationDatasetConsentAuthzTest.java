package org.tornotron.echno_backend.organization;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.organization.dto.DatasetConsentDto;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice authorization test for the dataset-consent endpoints (#790).
 *
 * <p>Consent is a legal statement about the client's data, so the guard is the organization's
 * system-admin alone: hr-admin, who may edit the organization's other fields through the patch
 * endpoint, is refused here. These tests pin that the guard is the {@code hasOrgRole} check on
 * the path id, that a refusal never reaches the service, and that the body must carry an
 * explicit value.
 */
@WebMvcTest(OrganizationWebController.class)
@Import({OrganizationDatasetConsentAuthzTest.TestSecurityConfig.class, JsonPartBinder.class,
        PayloadValidator.class})
class OrganizationDatasetConsentAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationService organizationService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @BeforeEach
    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void setDatasetConsent_recordsTheValueForTheOrganizationsSystemAdmin() throws Exception {
        when(orgSecurity.hasOrgRole(7L, "system-admin")).thenReturn(true);
        when(organizationService.setDatasetConsent(7L, true)).thenReturn(new DatasetConsentDto(7L, true));

        mockMvc.perform(put("/api/v1/organization/web/7/dataset-consent").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetConsent\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(7))
                .andExpect(jsonPath("$.datasetConsent").value(true));

        verify(organizationService).setDatasetConsent(7L, true);
    }

    @Test
    void setDatasetConsent_isRefusedForAMemberWithoutTheSystemAdminRole() throws Exception {
        // hr-admin can patch the organization but may not make this statement.
        when(orgSecurity.hasOrgRole(7L, "system-admin")).thenReturn(false);

        mockMvc.perform(put("/api/v1/organization/web/7/dataset-consent").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetConsent\": true}"))
                .andExpect(status().isForbidden());

        verify(organizationService, never()).setDatasetConsent(anyLong(), anyBoolean());
    }

    @Test
    void setDatasetConsent_guardsOnTheOrganizationInThePath() throws Exception {
        // system-admin of organization 7 gets nothing on organization 8.
        when(orgSecurity.hasOrgRole(7L, "system-admin")).thenReturn(true);
        when(orgSecurity.hasOrgRole(8L, "system-admin")).thenReturn(false);

        mockMvc.perform(put("/api/v1/organization/web/8/dataset-consent").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetConsent\": true}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(organizationService);
    }

    @Test
    void setDatasetConsent_rejectsABodyWithNoValue() throws Exception {
        // An absent value is not "false"; a retried or malformed request must not withdraw consent.
        when(orgSecurity.hasOrgRole(7L, "system-admin")).thenReturn(true);

        mockMvc.perform(put("/api/v1/organization/web/7/dataset-consent").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(organizationService);
    }

    @Test
    void readDatasetConsent_isOkForTheSystemAdminAndRefusedOtherwise() throws Exception {
        when(orgSecurity.hasOrgRole(7L, "system-admin")).thenReturn(true);
        when(organizationService.getDatasetConsent(7L)).thenReturn(new DatasetConsentDto(7L, false));

        mockMvc.perform(get("/api/v1/organization/web/7/dataset-consent").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetConsent").value(false));

        when(orgSecurity.hasOrgRole(7L, "system-admin")).thenReturn(false);
        mockMvc.perform(get("/api/v1/organization/web/7/dataset-consent").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void setDatasetConsent_isRefusedForAnAnonymousCaller() throws Exception {
        mockMvc.perform(put("/api/v1/organization/web/7/dataset-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetConsent\": true}"))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(organizationService);
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
