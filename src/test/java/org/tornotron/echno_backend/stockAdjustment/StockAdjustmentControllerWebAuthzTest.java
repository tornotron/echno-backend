package org.tornotron.echno_backend.stockAdjustment;

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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice authorization test for StockAdjustmentControllerWeb, whose endpoints split
 * the guard: any member of the current tenant may read, but only a system-admin or
 * project-manager may write. This pins both halves and, crucially, that a plain member
 * who holds neither role is refused a write. If the write guard were ever loosened to
 * membership, the member-without-role delete test fails. @orgSecurity is mocked so each
 * branch is exercised independently.
 */
@WebMvcTest(StockAdjustmentControllerWeb.class)
@Import(StockAdjustmentControllerWebAuthzTest.TestSecurityConfig.class)
class StockAdjustmentControllerWebAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockAdjustmentService stockAdjustmentService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here
    // because .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    // RPTExchangeFilter also depends on this cache; mocked for the same reason.
    @MockitoBean
    private RPTCache rptCache;

    @Test
    void read_isOk_forAnyMember() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(stockAdjustmentService.getAll(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/stock-adjustments/web").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void read_isForbidden_forANonMember() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);

        mockMvc.perform(get("/api/v1/stock-adjustments/web").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_isOk_forAnElevatedRoleHolder() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/stock-adjustments/web/1").with(jwt()).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void delete_isForbidden_forAPlainMemberWithoutAnElevatedRole() throws Exception {
        // A member may read but must not write: the write guard is the role, not membership.
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(false);

        mockMvc.perform(delete("/api/v1/stock-adjustments/web/1").with(jwt()).with(csrf()))
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
