package org.tornotron.echno_backend.modules.access.web;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;
import org.tornotron.echno_backend.modules.access.guard.CurrentAccessSubject;

/**
 * The caller's own access: an active member gets their grants with scopes, and anyone else is
 * refused.
 *
 * <p>{@code @access} is the real guard over a mocked subject and service, so the expression on the
 * handler is exercised as written.
 */
@WebMvcTest(CurrentUserAccessControllerWeb.class)
@Import({CurrentUserAccessControllerWebTest.TestSecurityConfig.class, AccessGuard.class})
class CurrentUserAccessControllerWebTest {

    private static final AccessSubject ALICE = new AccessSubject(7L, 1L, 11L);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentAccessSubject current;

    @MockitoBean
    private AccessService access;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void anActiveMemberGetsTheirGrantsWithScopes() throws Exception {
        when(current.resolve()).thenReturn(Optional.of(ALICE));
        when(access.isActiveMember(ALICE)).thenReturn(true);
        when(access.grantsFor(ALICE)).thenReturn(new AccessGrants(Map.of(
                "wbs:edit", EnumSet.of(AccessScope.OWN, AccessScope.ASSIGNED_PROJECTS),
                "chat:view", EnumSet.of(AccessScope.ALL))));

        mockMvc.perform(get("/api/v1/access/web/current-user-access").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(7))
                .andExpect(jsonPath("$.employeeId").value(1))
                .andExpect(jsonPath("$.grants['chat:view'][0]").value("ALL"))
                .andExpect(jsonPath("$.grants['wbs:edit'].length()").value(2))
                .andExpect(jsonPath("$.grants['wbs:edit'][0]").value("ASSIGNED_PROJECTS"))
                .andExpect(jsonPath("$.grants['wbs:edit'][1]").value("OWN"));
    }

    @Test
    void someoneWhoIsNotAnActiveMemberIsRefused() throws Exception {
        when(current.resolve()).thenReturn(Optional.of(ALICE));
        when(access.isActiveMember(ALICE)).thenReturn(false);

        mockMvc.perform(get("/api/v1/access/web/current-user-access").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRequestWithNoOrganizationOrMembershipIsRefused() throws Exception {
        when(current.resolve()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/access/web/current-user-access").with(jwt()))
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
