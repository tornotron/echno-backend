package org.tornotron.echno_backend.modules.toolboxtalks;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.modules.toolboxtalks.pdf.ToolboxTalkPdfService;
import org.tornotron.echno_backend.modules.toolboxtalks.service.ToolboxTalksService;
import org.tornotron.echno_backend.modules.toolboxtalks.web.ToolboxTalksController;
import org.tornotron.echno_backend.modules.toolboxtalks.web.ToolboxTalksControllerWeb;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.pdfGeneration.RenderedReport;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;

/**
 * The guard on every handler of both twins, at the slice: reads need membership of the tenant,
 * writes need one of the manage roles, and the same request is tried on both prefixes so the
 * twins cannot drift apart.
 */
@WebMvcTest({ToolboxTalksController.class, ToolboxTalksControllerWeb.class})
@Import(ToolboxTalksControllerWebAuthzTest.TestSecurityConfig.class)
class ToolboxTalksControllerWebAuthzTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String DRAFT = "{\"projectId\":1,\"topic\":\"Working at height\","
            + "\"talkDate\":\"2026-09-19\",\"conductorEmployeeId\":2}";
    private static final String CHANGE = "{\"topic\":\"Housekeeping\",\"talkDate\":\"2026-09-19\","
            + "\"conductorEmployeeId\":2}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private ToolboxTalksService service;

    @MockitoBean
    private ToolboxTalkPdfService pdfService;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    static Stream<Arguments> reads() {
        return twins(Stream.of(
                Arguments.of("list", (Endpoint) prefix -> get(prefix)),
                Arguments.of("get", (Endpoint) prefix -> get(prefix + "/" + ID)),
                Arguments.of("photos", (Endpoint) prefix -> get(prefix + "/" + ID + "/photos")),
                Arguments.of("pdf", (Endpoint) prefix -> get(prefix + "/" + ID + "/pdf"))));
    }

    static Stream<Arguments> writes() {
        return twins(Stream.of(
                Arguments.of("create", (Endpoint) prefix -> json(post(prefix), DRAFT)),
                Arguments.of("update", (Endpoint) prefix -> json(put(prefix + "/" + ID), CHANGE)),
                Arguments.of("addAttendees", (Endpoint) prefix ->
                        json(post(prefix + "/" + ID + "/attendees"), "{\"employeeIds\":[3]}")),
                Arguments.of("removeAttendee", (Endpoint) prefix -> delete(prefix + "/" + ID + "/attendees/3")),
                Arguments.of("record", (Endpoint) prefix -> post(prefix + "/" + ID + "/record")),
                Arguments.of("presign", (Endpoint) prefix -> json(post(prefix + "/" + ID + "/photos/presign"), "[]")),
                Arguments.of("register", (Endpoint) prefix -> json(post(prefix + "/" + ID + "/photos/register"), "[]"))));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("reads")
    void aReadIsForbiddenForANonMember(String name, String prefix, Endpoint endpoint) throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("reads")
    void aReadIsOkForAMember(String name, String prefix, Endpoint endpoint) throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(pdfService.render(any())).thenReturn(new RenderedReport("talk", "%PDF-".getBytes()));
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("writes")
    void aWriteIsForbiddenForAMemberWithoutAManageRole(String name, String prefix, Endpoint endpoint) throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("writes")
    void aWriteIsAllowedForAManageRole(String name, String prefix, Endpoint endpoint) throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().is2xxSuccessful());
    }

    private static Stream<Arguments> twins(Stream<Arguments> endpoints) {
        return endpoints.flatMap(args -> Stream.of(
                Arguments.of(args.get()[0], "/api/v1/toolbox-talks", args.get()[1]),
                Arguments.of(args.get()[0], "/api/v1/toolbox-talks/web", args.get()[1])));
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
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            return http.build();
        }
    }
}
