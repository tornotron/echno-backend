package org.tornotron.echno_backend.IssueComment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The mobile {@code IssueCommentController} was dead end to end: all three of its guards named an
 * {@code issue-comment} authority, and {@code JwtAuthConverter.extractPermissions} mints a
 * {@code resource:scope} authority only from the {@code authorization} claim of an RPT, which the
 * realm never populates because it defines no authorization scopes. See #734.
 *
 * <p>Nothing was lost while it stayed dead, because the web twin carries working versions, which
 * is exactly why it went unnoticed. It surfaced when #676 added an edit to the twin and
 * deliberately did not add one here: a new endpoint on this controller would have arrived
 * refusing every caller.
 *
 * <p>Repaired to the twin's answer rather than to the widest expression that makes the endpoints
 * reachable. Leaving a comment on an issue, and reading the comments on one, is any member's;
 * deleting somebody else's comment is a system admin's or a project manager's. The twin's edit
 * endpoint, which is the author's own, has no counterpart here and none is added: adding a route
 * is a contract change, and this repair deliberately changes no route.
 *
 * <p>Roles are stubbed as the exact list the guard names. A stub answering true to
 * {@code any(String[].class)} would keep passing if the delete quietly admitted every member.
 */
@WebMvcTest(IssueCommentController.class)
@Import(IssueCommentMobileGuardTest.TestSecurityConfig.class)
class IssueCommentMobileGuardTest {

    private static final long COMMENT = 11L;

    /** The pair the web twin's delete names. */
    private static final String[] DELETE_ROLES = {"system-admin", "project-manager"};

    /**
     * Valid against {@code IssueCommentCreationDto}. Body validation runs while arguments are
     * bound, which is before the method-security interceptor, so an invalid body would answer 400
     * and say nothing at all about the guard.
     */
    private static final String VALID_BODY = """
            {"comment":"Rebar spacing checked on the east face","issueId":42}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IssueCommentService issueCommentService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here because
    // .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    private void callerIsAnOutsider() {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
    }

    private void callerIsAPlainMember() {
        callerIsAnOutsider();
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
    }

    private void callerIsAProjectManager() {
        callerIsAPlainMember();
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(DELETE_ROLES)).thenReturn(true);
    }

    @Test
    void create_asAMemberOfTheTenant_isAllowed() throws Exception {
        callerIsAPlainMember();

        mockMvc.perform(post("/api/v1/issues/comments")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void create_asSomebodyOutsideTheTenant_isForbidden() throws Exception {
        callerIsAnOutsider();

        mockMvc.perform(post("/api/v1/issues/comments")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(issueCommentService);
    }

    /**
     * The listing calls {@code getContent()} on what the service returns, so the empty page has to
     * be stubbed. An unstubbed mock answers null and the request would fail on that rather than on
     * the guard, which is the assertion this test is making.
     */
    @Test
    void list_asAMemberOfTheTenant_isAllowed() throws Exception {
        callerIsAPlainMember();
        when(issueCommentService.getAllIssueComments(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/issues/comments").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void list_asSomebodyOutsideTheTenant_isForbidden() throws Exception {
        callerIsAnOutsider();

        mockMvc.perform(get("/api/v1/issues/comments").with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(issueCommentService);
    }

    @Test
    void delete_asAProjectManager_isAllowed() throws Exception {
        callerIsAProjectManager();

        mockMvc.perform(delete("/api/v1/issues/comments/{id}", COMMENT).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void delete_asAPlainMember_isForbidden() throws Exception {
        callerIsAPlainMember();

        mockMvc.perform(delete("/api/v1/issues/comments/{id}", COMMENT).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(issueCommentService);
    }

    /** The repaired guards keep no branch on the authority the realm cannot issue. */
    @Test
    void theOldAuthorityAloneOpensNothing() throws Exception {
        callerIsAnOutsider();

        mockMvc.perform(get("/api/v1/issues/comments")
                        .with(jwt().authorities(new SimpleGrantedAuthority("issue-comment:admin"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(issueCommentService);
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
