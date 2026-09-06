package org.tornotron.echno_backend.projectInviteCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.exception.TooManyAttemptsException;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a caller past the attempt allowance actually receives.
 *
 * <p>A limit whose refusal a client cannot act on is a limit that gets retried in a loop, so the
 * status has to be the one that means "later" and it has to carry when. The body must not,
 * because how much allowance is left is exactly what tells whoever provoked the refusal how to
 * pace the next run.
 */
@WebMvcTest(ProjectInviteCodeController.class)
@Import(InviteCodeRedemptionRefusalTest.TestSecurityConfig.class)
class InviteCodeRedemptionRefusalTest {

    private static final long USER_ID = 55L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectInviteCodeService projectInviteCodeService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void aSpentAllowanceAnswers429WithRetryAfterAndNoDetailOfTheAllowance() throws Exception {
        when(orgSecurity.isSelfUser(USER_ID)).thenReturn(true);
        when(projectInviteCodeService.validateAndUseInviteCode(any(), anyLong()))
                .thenThrow(new TooManyAttemptsException(
                        "Too many invite code attempts. Try again later.", Duration.ofMinutes(4)));

        mockMvc.perform(post("/api/v1/invitation/web/validate/userId/" + USER_ID)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"12345\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "240"))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.detail").value("Too many invite code attempts. Try again later."));
    }

    /**
     * Retry-After is expressed in whole seconds, so a wait shorter than one second must still ask
     * for at least one. Rounding it to zero would invite the immediate retry the header exists to
     * prevent.
     */
    @Test
    void aSubSecondWaitIsStillReportedAsASecond() throws Exception {
        when(orgSecurity.isSelfUser(USER_ID)).thenReturn(true);
        when(projectInviteCodeService.validateAndUseInviteCode(any(), anyLong()))
                .thenThrow(new TooManyAttemptsException("Too many invite code attempts. Try again later.",
                        Duration.ofMillis(200)));

        mockMvc.perform(post("/api/v1/invitation/web/validate/userId/" + USER_ID)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"12345\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
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
