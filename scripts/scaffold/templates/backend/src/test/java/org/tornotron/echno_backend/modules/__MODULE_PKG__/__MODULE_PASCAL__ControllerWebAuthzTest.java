package org.tornotron.echno_backend.modules.__MODULE_PKG__;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.service.__MODULE_PASCAL__Service;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.web.__MODULE_PASCAL__Controller;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.web.__MODULE_PASCAL__ControllerWeb;

/**
 * Authorization on both twins: reads need tenant membership, the write needs an admin role.
 *
 * <p>Method security comes from a minimal test filter chain and {@code @orgSecurity} is mocked,
 * as in {@code ModuleControllerWebAuthzTest}. The service is mocked too: this slice proves the
 * guard, not the behaviour behind it, which {@code __MODULE_PASCAL__ServiceIT} covers.
 */
@WebMvcTest({__MODULE_PASCAL__Controller.class, __MODULE_PASCAL__ControllerWeb.class})
@Import(__MODULE_PASCAL__ControllerWebAuthzTest.TestSecurityConfig.class)
class __MODULE_PASCAL__ControllerWebAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private __MODULE_PASCAL__Service service;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void mobileList_isForbidden_forANonMember() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);

        mockMvc.perform(get("/api/v1/__MODULE_ID__").with(memberOfOrgSeven()))
                .andExpect(status().isForbidden());
    }

    @Test
    void mobileGet_isOk_forAMember() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);

        mockMvc.perform(get("/api/v1/__MODULE_ID__/" + UUID.randomUUID()).with(memberOfOrgSeven()))
                .andExpect(status().isOk());
    }

    @Test
    void webList_isOk_forAMember() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);

        mockMvc.perform(get("/api/v1/__MODULE_ID__/web").with(memberOfOrgSeven()))
                .andExpect(status().isOk());
    }

    @Test
    void webCreate_isForbidden_forAMemberWithoutAnAdminRole() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);

        mockMvc.perform(post("/api/v1/__MODULE_ID__/web").with(memberOfOrgSeven())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"First\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void webCreate_isCreated_forAnAdmin() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);

        mockMvc.perform(post("/api/v1/__MODULE_ID__/web").with(memberOfOrgSeven())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"First\"}"))
                .andExpect(status().isCreated());
    }

    private static RequestPostProcessor memberOfOrgSeven() {
        return jwt().authorities(new SimpleGrantedAuthority("ORG_MEMBER_7"));
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
