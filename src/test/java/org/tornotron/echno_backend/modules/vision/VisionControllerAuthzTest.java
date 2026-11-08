package org.tornotron.echno_backend.modules.vision;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.vision.service.CvModelRegistryService;
import org.tornotron.echno_backend.modules.vision.service.VisionRunService;
import org.tornotron.echno_backend.modules.vision.service.VisionSettingsService;
import org.tornotron.echno_backend.modules.vision.web.VisionModelAdminController;
import org.tornotron.echno_backend.modules.vision.web.VisionModelController;
import org.tornotron.echno_backend.modules.vision.web.VisionWebController;

/**
 * The guards with the module switched on: reading the registry needs a reviewing role in the tenant,
 * and writing it needs the platform-admin role, which an organization's own system-admin does not hold.
 */
@WebMvcTest({VisionModelController.class, VisionModelAdminController.class, VisionWebController.class})
@TestPropertySource(properties = VisionModuleEnabled.PROPERTY + "=true")
@Import(VisionControllerAuthzTest.TestSecurityConfig.class)
class VisionControllerAuthzTest {

    private static final String SETTINGS = "{\"autoInfer\":true,\"modelKeys\":[\"ppe\"]}";
    private static final String RUN = "{\"attachmentIds\":[18342]}";
    private static final String FAMILY =
            "{\"key\":\"ppe\",\"name\":\"PPE\",\"task\":\"DETECT\",\"family\":\"SAFETY\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private CvModelRegistryService service;

    @MockitoBean
    private VisionSettingsService settings;

    @MockitoBean
    private VisionRunService runs;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void readingTheRegistryIsForbiddenWithoutAReviewingRole() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);

        mockMvc.perform(get("/api/v1/vision/models").with(member())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/vision/models/ppe/versions/0.1.0").with(member())).andExpect(status().isForbidden());
    }

    @Test
    void readingTheRegistryIsOkWithAReviewingRole() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);

        mockMvc.perform(get("/api/v1/vision/models").with(member())).andExpect(status().isOk());
    }

    @Test
    void anOrganizationsSystemAdminCannotWriteTheRegistry() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);

        mockMvc.perform(post("/api/v1/vision/admin/models").with(orgAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(FAMILY))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPlatformAdminCanWriteTheRegistry() throws Exception {
        mockMvc.perform(post("/api/v1/vision/admin/models")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_platform-admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(FAMILY))
                .andExpect(status().isCreated());
    }

    @Test
    void theSettingsAreReadByAReviewingRoleAndWrittenOnlyByTheOrganizationsAdmin() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(false);

        mockMvc.perform(get("/api/v1/vision/web/settings").with(member())).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/vision/web/settings").with(member())
                        .contentType(MediaType.APPLICATION_JSON).content(SETTINGS))
                .andExpect(status().isForbidden());

        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(true);

        mockMvc.perform(put("/api/v1/vision/web/settings").with(orgAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(SETTINGS))
                .andExpect(status().isOk());
    }

    @Test
    void runningNeedsAReviewingRole() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);

        mockMvc.perform(post("/api/v1/vision/web/runs").with(member())
                        .contentType(MediaType.APPLICATION_JSON).content(RUN))
                .andExpect(status().isForbidden());

        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);

        mockMvc.perform(post("/api/v1/vision/web/runs").with(member())
                        .contentType(MediaType.APPLICATION_JSON).content(RUN))
                .andExpect(status().isOk());
    }

    @Test
    void settingsWithoutTheirFieldsAreRefused() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);

        mockMvc.perform(put("/api/v1/vision/web/settings").with(orgAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor member() {
        return jwt().authorities(new SimpleGrantedAuthority("ORG_MEMBER_7"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor orgAdmin() {
        return jwt().authorities(new SimpleGrantedAuthority("ORG_MEMBER_7"),
                new SimpleGrantedAuthority("ORG_7_ROLE_system-admin"));
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
