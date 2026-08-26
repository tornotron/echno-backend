package org.tornotron.echno_backend.chat.realtime;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.tornotron.echno_backend.chat.ChatControllerWeb;
import org.tornotron.echno_backend.chat.ChatService;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice tests for the chat stream endpoint.
 *
 * <p>Covers the parts of the design that live in the HTTP response rather than in the registry:
 * that the request goes asynchronous instead of completing, that the buffering opt-out is on the
 * response, that the opening frame is not named in a way the browser would confuse with its own
 * open event, that a delivered event actually reaches the wire, and that the authorization guard
 * is present.
 */
@WebMvcTest(ChatControllerWeb.class)
@Import({ChatStreamEndpointTest.TestSecurityConfig.class, ChatStreamService.class, ChatStreamRegistry.class})
class ChatStreamEndpointTest {

    private static final Long ORG = 1L;
    private static final Long ALICE = 10L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatStreamRegistry registry;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, which the web slice loads; unused because jwt() sets the
    // authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private MvcResult openStream() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(chatService.getCurrentEmployeeId()).thenReturn(ALICE);
        // The controller reads the tenant from the thread the request runs on, which the real
        // TenantFilter populates. The slice does not load that filter, so set it directly.
        TenantContext.setCurrentOrgId(ORG);

        return mockMvc.perform(get("/api/v1/chat/stream").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @Test
    void openingTheStreamGoesAsynchronousAndDisablesProxyBuffering() throws Exception {
        MvcResult result = openStream();

        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        // nginx buffers proxied responses for every site at the edge; without this header the
        // events would sit in that buffer instead of reaching the browser.
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void theOpeningFrameSetsTheReconnectDelayAndIsNotNamedOpen() throws Exception {
        MvcResult result = openStream();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("retry:1000");
        assertThat(body).contains("event:ready");
        // "open" would be dispatched to the browser as an event of that name and would then be
        // indistinguishable from EventSource's own open event, which the client uses to tell a
        // reconnect from a first connection.
        assertThat(body).doesNotContain("event:open");
    }

    @Test
    void anEventForTheCallerIsWrittenToTheOpenStream() throws Exception {
        MvcResult result = openStream();

        registry.deliver(new ChatEvent(
                ChatEventType.MESSAGE_CREATED, ORG, 5L, 99L, 11L, List.of(ALICE)));

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:chat");
        assertThat(body).contains("\"roomId\":5");
        assertThat(body).contains("MESSAGE_CREATED");
        // Routing metadata stays server side: the browser is told what changed, not who else
        // was notified or which tenant it belongs to.
        assertThat(body).doesNotContain("recipients");
        assertThat(body).doesNotContain("orgId");
    }

    @Test
    void theHeartbeatIsWrittenToTheOpenStream() throws Exception {
        MvcResult result = openStream();

        registry.heartbeat();

        // An SSE comment: no event, no data, just traffic to keep the proxies from timing the
        // connection out.
        assertThat(result.getResponse().getContentAsString()).contains(":keep-alive");
    }

    @Test
    void theStreamIsRefusedToANonMemberOfTheTenant() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);

        mockMvc.perform(get("/api/v1/chat/stream").with(jwt()))
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
