package org.tornotron.echno_backend.modules.documentingestion;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.filter.OncePerRequestFilter;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionStatusController;

/**
 * The gate at the web slice: what each state of the switch and the allow-list answers, and that the
 * gate is asked before the role guard.
 */
class DocumentIngestionGateWebTest {

    private static final long ORG = 7L;
    private static final String STATUS = "/api/v1/document-ingestion/status";

    private static RequestPostProcessor memberOfOrgSeven() {
        return jwt().authorities(new SimpleGrantedAuthority("ORG_MEMBER_" + ORG));
    }

    @WebMvcTest(DocumentIngestionStatusController.class)
    @Import(TestSecurityConfig.class)
    abstract static class Slice {

        @Autowired
        MockMvc mockMvc;

        @MockitoBean(name = "access")
        AccessGuard access;

        @MockitoBean
        ModuleRegistry registry;

        @MockitoBean
        KeycloakAuthorizationService keycloakAuthorizationService;

        @MockitoBean
        RPTCache rptCache;

        @BeforeEach
        void aMemberOfTheTenant() {
            when(access.can("document-ingestion", "read")).thenReturn(true);
        }
    }

    @Nested
    class SwitchOff extends Slice {

        @Test
        void refusesWithTheStableCodeBeforeTheRoleGuardIsAsked() throws Exception {
            mockMvc.perform(get(STATUS).with(memberOfOrgSeven()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MODULE_DISABLED"))
                    .andExpect(jsonPath("$.moduleId").value("document-ingestion"))
                    .andExpect(jsonPath("$.reason").value("SWITCHED_OFF"));
            verifyNoInteractions(access);
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "echno.modules.document-ingestion.enabled=true",
            "echno.modules.document-ingestion.allowed-org-ids="})
    class SwitchOnWithAnEmptyAllowList extends Slice {

        @Test
        void refusesEveryOrganization() throws Exception {
            mockMvc.perform(get(STATUS).with(memberOfOrgSeven()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MODULE_DISABLED"))
                    .andExpect(jsonPath("$.reason").value("NOT_ALLOWED"));
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "echno.modules.document-ingestion.enabled=true",
            "echno.modules.document-ingestion.allowed-org-ids=7"})
    class SwitchOnAndAllowListed extends Slice {

        @Test
        void answersWhenTheRegistryAgrees() throws Exception {
            when(registry.isEnabledForOrg("document-ingestion", ORG)).thenReturn(true);

            mockMvc.perform(get(STATUS).with(memberOfOrgSeven()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.moduleId").value("document-ingestion"));
        }

        @Test
        void refusesWhenEnforcedEntitlementSaysNo() throws Exception {
            when(registry.isEnabledForOrg("document-ingestion", ORG)).thenReturn(false);

            mockMvc.perform(get(STATUS).with(memberOfOrgSeven()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MODULE_DISABLED"))
                    .andExpect(jsonPath("$.reason").value("NOT_ENTITLED"));
        }

        @Test
        void refusesACallerWithNoTenant() throws Exception {
            mockMvc.perform(get(STATUS).with(jwt()).header("X-Skip-Tenant", "true"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.reason").value("NO_ORGANIZATION"));
        }

        @Test
        void theRoleGuardStillAppliesOnceTheModuleIsOpen() throws Exception {
            when(registry.isEnabledForOrg("document-ingestion", ORG)).thenReturn(true);
            when(access.can("document-ingestion", "read")).thenReturn(false);

            mockMvc.perform(get(STATUS).with(memberOfOrgSeven()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").doesNotExist());
        }
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {

        @Bean
        SecurityFilterChain testChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            http.addFilterAfter(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                                jakarta.servlet.http.HttpServletResponse response,
                                                jakarta.servlet.FilterChain chain)
                        throws java.io.IOException, jakarta.servlet.ServletException {
                    try {
                        if (request.getHeader("X-Skip-Tenant") == null) {
                            TenantContext.setCurrentOrgId(ORG);
                        }
                        chain.doFilter(request, response);
                    } finally {
                        TenantContext.clear();
                    }
                }
            }, org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
            return http.build();
        }
    }
}
