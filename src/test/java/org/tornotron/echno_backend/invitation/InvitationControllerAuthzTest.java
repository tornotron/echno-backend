package org.tornotron.echno_backend.invitation;

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
import org.tornotron.echno_backend.employee.TeamAccess;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only a caller who may invite across the organization reaches the invitation endpoints. */
@WebMvcTest(InvitationControllerWeb.class)
@Import(InvitationControllerAuthzTest.TestSecurityConfig.class)
class InvitationControllerAuthzTest {

    private static final String BODY = """
            {"email": "new.person@example.test", "roles": [], "employee": {"status": "active"}}
            """;

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private InvitationService invitations;
    @MockitoBean(name = "access")
    private AccessGuard access;
    @MockitoBean(name = "teamAccess")
    private TeamAccess teamAccess;
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;
    @MockitoBean
    private RPTCache rptCache;

    private void memberWithAdminRole(boolean admin) {
        when(access.can("team", "invite")).thenReturn(admin);
        when(teamAccess.acrossOrganization("invite")).thenReturn(admin);
    }

    @Test
    void aMemberWithoutTheRoleIsRefusedEverywhere() throws Exception {
        memberWithAdminRole(false);
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/invitations/web").contentType(MediaType.APPLICATION_JSON).content(BODY).with(jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/invitations/web").with(jwt())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/invitations/web/" + id).with(jwt())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/invitations/web/" + id + "/resend").with(jwt())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/invitations/web/" + id + "/revoke").with(jwt())).andExpect(status().isForbidden());
        verifyNoInteractions(invitations);
    }

    @Test
    void aSystemOrHrAdministratorReachesThem() throws Exception {
        memberWithAdminRole(true);

        mockMvc.perform(post("/api/v1/invitations/web").contentType(MediaType.APPLICATION_JSON).content(BODY).with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/invitations/web").with(jwt())).andExpect(status().isOk());
    }

    @Test
    void anInvalidAddressIsRefusedBeforeTheService() throws Exception {
        memberWithAdminRole(true);

        mockMvc.perform(post("/api/v1/invitations/web").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("new.person@example.test", "not-an-address")).with(jwt()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(invitations);
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
