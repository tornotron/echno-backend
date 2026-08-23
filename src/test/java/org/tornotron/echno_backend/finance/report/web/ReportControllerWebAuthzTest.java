package org.tornotron.echno_backend.finance.report.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.finance.report.dtos.TrialBalanceReport;
import org.tornotron.echno_backend.finance.report.service.ReportService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice authorization test for ReportControllerWeb. The financial reports are
 * restricted to the elevated finance roles, so this pins that the guard admits a caller
 * holding system-admin or project-manager for the current tenant and forbids one with
 * neither. The stubs use exact role arguments, so the test also proves the controller
 * passes precisely those two role names to @orgSecurity; @orgSecurity itself is mocked.
 */
@WebMvcTest(ReportControllerWeb.class)
@Import(ReportControllerWebAuthzTest.TestSecurityConfig.class)
class ReportControllerWebAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportService reportService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @Test
    void trialBalance_isOk_forAnElevatedFinanceRoleHolder() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(true);
        when(reportService.trailBalanceReport(any()))
                .thenReturn(new TrialBalanceReport(null, List.of(), null, null, true));

        mockMvc.perform(get("/api/v1/finance/reports/web/trial-balance")
                        .param("asOfDate", "2026-01-01").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void trialBalance_isForbidden_forACallerWithoutAnElevatedFinanceRole() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(false);

        mockMvc.perform(get("/api/v1/finance/reports/web/trial-balance")
                        .param("asOfDate", "2026-01-01").with(jwt()))
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
