package org.tornotron.echno_backend.siteTransfer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;


import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice authorization test for SiteTransferControllerWeb. Reads admit the system-admin
 * and project-manager roles; everything that moves stock admits system-admin alone.
 *
 * <p>The project-manager case used to assert a refusal on the listing, and it is now an
 * admittance: #680 opened the five procurement document reads so the role that already
 * reads every movement through the inventory ledger can open the documents those movements
 * came from, and #695 is the case that made it concrete, a project manager raising the stock
 * adjustment that settles a transfer receipt variance while being refused the transfer it
 * names. The refusal that remains is the one that always mattered here: receiving posts stock
 * into a project and cancelling takes it back out.
 *
 * <p>The stubs use exact role arguments, so what is pinned is the role list each guard asks
 * for rather than any role at all. @orgSecurity is mocked. The wider read/write split across
 * all five documents is in ProcurementDocumentGuardSplitTest.
 */
@WebMvcTest(SiteTransferControllerWeb.class)
@Import(SiteTransferControllerWebAuthzTest.TestSecurityConfig.class)
class SiteTransferControllerWebAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SiteTransferService siteTransferService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here
    // because .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    // RPTExchangeFilter also depends on this cache; mocked for the same reason.
    @MockitoBean
    private RPTCache rptCache;

    /**
     * The exact-argument stub is what carries this test. The guard asks for all three roles in a
     * single call, so this matches only a guard that names exactly {@code system-admin},
     * {@code store-keeper} and {@code project-manager}: a stub of a narrower call would not match,
     * and the read would answer 403. The mock cannot say which of the three the caller holds, which
     * is why the refusal below is stubbed on the same call returning false. That the read admits
     * {@code project-manager} in particular is asserted by
     * {@code ProcurementDocumentGuardSplitTest#everyDocumentReadAdmitsTheProjectManager}, which
     * reads the annotation rather than the mock.
     */
    @Test
    void readAll_isOk_forAnAdmittedRole() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "store-keeper", "project-manager"))
                .thenReturn(true);
        when(siteTransferService.getAllSiteTransfers(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/site-transfers/web").with(jwt()))
                .andExpect(status().isOk());
    }

    /**
     * The refusal a project manager used to get from this read was removed with #695: a project
     * manager raises the stock adjustment that settles a receipt variance and now reads the
     * transfer that adjustment names. What stays asserted here is that a caller holding none of
     * the three admitted roles is refused.
     */
    @Test
    void readAll_isForbidden_forACallerWithNoElevatedRole() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "store-keeper", "project-manager"))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/site-transfers/web").with(jwt()))
                .andExpect(status().isForbidden());
    }

    /**
     * The receive endpoint posts stock into a project, so a caller who cannot be trusted with the
     * rest of this controller must not reach it either. It is gated on the same role rather than
     * being left open because it is new.
     */
    @Test
    void receive_isForbidden_forACallerWithNoElevatedRole() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "store-keeper")).thenReturn(false);

        mockMvc.perform(post("/api/v1/site-transfers/web/51/receive").with(jwt()).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"items\":[{\"itemId\":84,\"receivedQuantity\":8}]}"))
                .andExpect(status().isForbidden());
    }

    /** Cancelling returns stock to the sending project, so it is gated the same way. */
    @Test
    void cancel_isForbidden_forACallerWithNoElevatedRole() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "store-keeper")).thenReturn(false);

        mockMvc.perform(post("/api/v1/site-transfers/web/51/cancel").with(jwt()).with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"Lorry turned back\"}"))
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
