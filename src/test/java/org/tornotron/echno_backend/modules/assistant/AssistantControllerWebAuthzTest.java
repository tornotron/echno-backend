package org.tornotron.echno_backend.modules.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.dto.IngestStatusDto;
import org.tornotron.echno_backend.modules.assistant.ingest.DocumentIngestionService;
import org.tornotron.echno_backend.modules.assistant.service.AssistantService;
import org.tornotron.echno_backend.modules.assistant.web.AssistantController;
import org.tornotron.echno_backend.modules.assistant.web.AssistantControllerWeb;

/**
 * The guard on every handler of both twins, at the slice: asking and reading your own history need
 * membership of the tenant, the audit listing needs the system admin, and the same request is tried
 * on both prefixes so the twins cannot drift apart. The audit listing is on the web twin only, and
 * the mobile twin says so by having no such route.
 */
@WebMvcTest({AssistantController.class, AssistantControllerWeb.class})
@Import(AssistantControllerWebAuthzTest.TestSecurityConfig.class)
@TestPropertySource(properties = AssistantModuleEnabled.PROPERTY + "=true")
class AssistantControllerWebAuthzTest {

    private static final String QUESTION = "{\"question\":\"How many workers were on site at Tower B this week?\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private AssistantService service;

    @MockitoBean
    private DocumentIngestionService ingestionService;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    static Stream<Arguments> memberEndpoints() {
        return twins(Stream.of(
                Arguments.of("ask", (Endpoint) prefix -> json(post(prefix + "/ask"), QUESTION)),
                Arguments.of("queries", (Endpoint) prefix -> get(prefix + "/queries"))));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("memberEndpoints")
    void aNonMemberIsForbidden(String name, String prefix, Endpoint endpoint) throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("memberEndpoints")
    void aMemberIsAllowedWithNoRoleAtAll(String name, String prefix, Endpoint endpoint) throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(service.history(0, 20)).thenReturn(new PageImpl<>(List.of()));
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "on {0}")
    @MethodSource("prefixes")
    void aBlankQuestionIsRefusedAsABadRequest(String prefix) throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        mockMvc.perform(json(post(prefix + "/ask"), "{\"question\":\"   \"}").with(memberOfOrgSeven()))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "on {0}")
    @MethodSource("prefixes")
    void aQuestionPastTheLengthLimitIsRefused(String prefix) throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        mockMvc.perform(json(post(prefix + "/ask"), "{\"question\":\"" + "x".repeat(1001) + "\"}").with(memberOfOrgSeven()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theAuditListingIsForbiddenForAMemberWhoIsNotASystemAdmin() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
        mockMvc.perform(get("/api/v1/assistant/web/audit").with(memberOfOrgSeven())).andExpect(status().isForbidden());
    }

    @Test
    void theAuditListingIsAllowedForASystemAdmin() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(service.audit(0, 20)).thenReturn(new PageImpl<>(List.of()));
        mockMvc.perform(get("/api/v1/assistant/web/audit").with(memberOfOrgSeven())).andExpect(status().isOk());
    }

    @Test
    void theMobileTwinHasNoAuditListing() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        mockMvc.perform(get("/api/v1/assistant/audit").with(memberOfOrgSeven())).andExpect(status().isNotFound());
    }

    @Test
    void nobodyUnauthenticatedGetsAnythingFromEitherTwin() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/ask").contentType(MediaType.APPLICATION_JSON).content(QUESTION))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/assistant/web/audit")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/assistant/ingest").contentType(MediaType.APPLICATION_JSON).content("{\"attachmentId\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/assistant/web/ingest/1/status")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "on {0}")
    @MethodSource("prefixes")
    void ingestAndStatusAreForbiddenWithoutManagerOrAdminRole(String prefix) throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
        mockMvc.perform(json(post(prefix + "/ingest"), "{\"attachmentId\":10,\"projectId\":42}").with(memberOfOrgSeven()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(prefix + "/ingest/10/status").with(memberOfOrgSeven()))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "on {0}")
    @MethodSource("prefixes")
    void ingestAndStatusAreAllowedForProjectManagerOrAdmin(String prefix) throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(ingestionService.ingest(10L, 42L))
                .thenReturn(new IngestStatusDto(10L, "COMPLETED", 3, "Document ingested successfully."));
        when(ingestionService.status(10L))
                .thenReturn(new IngestStatusDto(10L, "COMPLETED", 3, "Document is indexed and ready."));
        mockMvc.perform(json(post(prefix + "/ingest"), "{\"attachmentId\":10,\"projectId\":42}").with(memberOfOrgSeven()))
                .andExpect(status().isCreated());
        mockMvc.perform(get(prefix + "/ingest/10/status").with(memberOfOrgSeven()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "on {0}")
    @MethodSource("prefixes")
    void ingestWithoutAttachmentIdIsRefusedAsBadRequest(String prefix) throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        mockMvc.perform(json(post(prefix + "/ingest"), "{\"projectId\":42}").with(memberOfOrgSeven()))
                .andExpect(status().isBadRequest());
    }

    static Stream<String> prefixes() {
        return Stream.of("/api/v1/assistant", "/api/v1/assistant/web");
    }

    private static Stream<Arguments> twins(Stream<Arguments> endpoints) {
        return endpoints.flatMap(args -> Stream.of(
                Arguments.of(args.get()[0], "/api/v1/assistant", args.get()[1]),
                Arguments.of(args.get()[0], "/api/v1/assistant/web", args.get()[1])));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor memberOfOrgSeven() {
        return jwt().authorities(new SimpleGrantedAuthority("ORG_MEMBER_7"));
    }

    @FunctionalInterface
    interface Endpoint {
        MockHttpServletRequestBuilder on(String prefix);
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(e -> e.authenticationEntryPoint(
                            new org.springframework.security.web.authentication.HttpStatusEntryPoint(
                                    org.springframework.http.HttpStatus.UNAUTHORIZED)));
            return http.build();
        }
    }
}
