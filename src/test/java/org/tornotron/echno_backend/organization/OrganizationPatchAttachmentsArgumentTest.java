package org.tornotron.echno_backend.organization;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.mockito.ArgumentCaptor;

/**
 * What the organization PATCH handler receives when the request carries no attachments part.
 *
 * <p>This is the half of the null-logo crash that cannot be read off the service. The handler
 * declares {@code @RequestParam(value = "attachments", required = false) List<MultipartFile>},
 * and the question is whether an absent part reaches it as null or as an empty list. It reaches
 * it as null, so the loop in the service ran over nothing at all.
 *
 * <p>The service is mocked here on purpose: this case is about the argument the framework builds,
 * not about what the service does with it. {@link OrganizationPatchWithoutAttachmentsTest} takes
 * the other half.
 */
@WebMvcTest(OrganizationWebController.class)
@Import({OrganizationPatchAttachmentsArgumentTest.TestSecurityConfig.class, JsonPartBinder.class,
        PayloadValidator.class})
class OrganizationPatchAttachmentsArgumentTest {

    private static final Long ORG_ID = 7L;

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
    void allowTheCaller() {
        TenantContext.setCurrentOrgId(ORG_ID);
        when(orgSecurity.hasAnyOrgRole(anyLong(), anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void anEditWithNoLogo_reachesTheServiceWithANullAttachmentsList() throws Exception {
        when(organizationService.partialUpdateAnOrganization(any(), anyLong(), any(), anyString()))
                .thenReturn(new OrganizationSimpleDto());

        MockMultipartFile data = new MockMultipartFile(
                "data", "", MediaType.APPLICATION_JSON_VALUE,
                "{\"organizationName\":\"Asset Homes Kerala\"}".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/v1/organization/web/{id}", ORG_ID)
                        .file(data)
                        .with(jwt()))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MultipartFile>> attachments =
                ArgumentCaptor.forClass((Class<List<MultipartFile>>) (Class<?>) List.class);
        verify(organizationService).partialUpdateAnOrganization(
                any(), anyLong(), attachments.capture(), anyString());

        // Null, not an empty list. Iterating this without a guard is the crash.
        assertThat(attachments.getValue()).isNull();
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
