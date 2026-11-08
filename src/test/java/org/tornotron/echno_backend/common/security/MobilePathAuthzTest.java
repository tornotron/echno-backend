package org.tornotron.echno_backend.common.security;

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
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.expense.ExpenseController;
import org.tornotron.echno_backend.expense.ExpenseControllerWeb;
import org.tornotron.echno_backend.expense.ExpenseService;
import org.tornotron.echno_backend.modules.inspections.pdf.NcrReportPdfService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionSecurityService;
import org.tornotron.echno_backend.modules.inspections.service.NcrService;
import org.tornotron.echno_backend.modules.inspections.service.ReinspectionService;
import org.tornotron.echno_backend.modules.inspections.web.NcrController;
import org.tornotron.echno_backend.modules.inspections.web.ReinspectionController;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The mobile paths added for the web-only modules (#998) are guarded through MockMvc, not only on
 * paper. {@code MobilePathGuardParityTest} pins that each guard is the web twin's text; this pins
 * that Spring actually routes the un-suffixed path to the handler and evaluates that guard there.
 *
 * <p>One controller per way the mobile paths are built: {@link ExpenseController} only sets the
 * class path and inherits every handler and guard, {@link NcrController} does the same with a
 * guard bound to a path variable ({@code #id}), and {@link ReinspectionController} overrides its
 * handlers and repeats a guard bound to {@code #ncrId}. A guard bound to a parameter is the one
 * that would quietly fail closed if inheritance lost the parameter names.
 */
@WebMvcTest({ExpenseController.class, ExpenseControllerWeb.class, NcrController.class, ReinspectionController.class})
@Import(MobilePathAuthzTest.TestSecurityConfig.class)
class MobilePathAuthzTest {

    private static final UUID NCR = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_NCR = UUID.fromString("66666666-7777-8888-9999-000000000000");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean(name = "inspectionSecurity")
    private InspectionSecurityService inspectionSecurity;

    // Named to match the @access bean the expense guards reference, now on the access matrix.
    @MockitoBean(name = "access")
    private org.tornotron.echno_backend.modules.access.guard.AccessGuard access;

    @MockitoBean
    private ExpenseService expenseService;

    @MockitoBean
    private NcrService ncrService;

    @MockitoBean
    private NcrReportPdfService ncrReportPdfService;

    @MockitoBean
    private ReinspectionService reinspectionService;

    // RPTExchangeFilter, which the web slice loads, needs both; .with(jwt()) authenticates directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    // ---- inherited handler and access guards ----

    @Test
    void aCallerWhoMayReadExpensesReadsThemOnTheMobilePath() throws Exception {
        when(access.can("expenses", "read")).thenReturn(true);

        mockMvc.perform(get("/api/v1/expenses/5").with(jwt())).andExpect(status().isOk());
    }

    @Test
    void aCallerWhoMayNotReadExpensesIsRefusedOnTheMobilePath() throws Exception {
        when(access.can("expenses", "read")).thenReturn(false);

        mockMvc.perform(get("/api/v1/expenses/5").with(jwt())).andExpect(status().isForbidden());
    }

    @Test
    void aCallerWhoMayReadButNotDeleteIsRefusedADeleteOnTheMobilePath() throws Exception {
        when(access.can("expenses", "read")).thenReturn(true);
        when(access.can("expenses", "delete")).thenReturn(false);

        mockMvc.perform(delete("/api/v1/expenses/5").with(jwt())).andExpect(status().isForbidden());
    }

    @Test
    void aCallerWhoMayDeleteDeletesOnTheMobilePath() throws Exception {
        when(access.can("expenses", "delete")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/expenses/5").with(jwt())).andExpect(status().isOk());
    }

    @Test
    void anUnauthenticatedCallerIsRefusedOnTheMobilePath() throws Exception {
        // No token at all: refused before any guard or service is reached.
        mockMvc.perform(get("/api/v1/expenses/5")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/v1/inspections/reinspections/{id}", NCR)).andExpect(status().is4xxClientError());
        verifyNoInteractions(expenseService, reinspectionService);
    }

    /** Both controllers are registered side by side, so the web path is untouched by the mobile one. */
    @Test
    void theWebPathStillAnswersBesideIt() throws Exception {
        when(access.can("expenses", "read")).thenReturn(true);

        mockMvc.perform(get("/api/v1/expenses/web/5").with(jwt())).andExpect(status().isOk());
    }

    // ---- inherited handler, guard bound to a path variable ----

    @Test
    void anInheritedGuardIsEvaluatedAgainstThePathVariable() throws Exception {
        when(inspectionSecurity.canSignOffNcr(NCR)).thenReturn(true);
        when(inspectionSecurity.canSignOffNcr(OTHER_NCR)).thenReturn(false);

        mockMvc.perform(post("/api/v1/ncrs/{id}/verify", NCR).with(jwt()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/ncrs/{id}/verify", OTHER_NCR).with(jwt()))
                .andExpect(status().isForbidden());
    }

    // ---- overridden handler, repeated guard ----

    @Test
    void aReaderGetsAReinspectionOnTheMobilePath() throws Exception {
        when(inspectionSecurity.canRead()).thenReturn(true);

        mockMvc.perform(get("/api/v1/inspections/reinspections/{id}", NCR).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void aCallerWhoCannotReadIsRefusedAReinspectionOnTheMobilePath() throws Exception {
        when(inspectionSecurity.canRead()).thenReturn(false);

        mockMvc.perform(get("/api/v1/inspections/reinspections/{id}", NCR).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOverriddenGuardIsEvaluatedAgainstThePathVariable() throws Exception {
        when(inspectionSecurity.canSignOffNcr(NCR)).thenReturn(true);
        when(inspectionSecurity.canSignOffNcr(OTHER_NCR)).thenReturn(false);

        mockMvc.perform(post("/api/v1/ncrs/{ncrId}/reinspections", NCR).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/ncrs/{ncrId}/reinspections", OTHER_NCR).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
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
