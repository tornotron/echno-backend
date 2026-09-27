package org.tornotron.echno_backend.wbs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.tornotron.echno_backend.wbs.dto.WbsElementDto;
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
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

/**
 * The schedule's guards on both twins, at the slice: any member of the tenant reads the WBS and
 * the schedule, and only the system admin or the project manager changes it. Before work progress
 * inspection every endpoint here was the system admin's alone.
 */
@WebMvcTest({WbsElementController.class, WbsElementControllerWeb.class})
@Import(WbsElementControllerAuthzTest.TestSecurityConfig.class)
class WbsElementControllerAuthzTest {

    private static final String MOBILE = "/api/v1/project/5/wbs";
    private static final String WEB = "/api/v1/project/5/wbs/web";
    private static final String CREATE = "{\"wbsCode\":\"1.1\",\"title\":\"Footings\"}";
    private static final String LINK = "{\"predecessorId\":1,\"successorId\":2}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private WbsElementService service;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    // The controllers log the created element's id and the batch size, so the mocked service
    // hands back an empty element and an empty batch rather than null.
    @BeforeEach
    void stubCreates() {
        when(service.createWbsElement(any(), any())).thenReturn(new WbsElementDto());
        when(service.bulkCreateWbsElements(any(), any())).thenReturn(List.of());
    }

    static Stream<Arguments> reads() {
        return twins(Stream.of(
                Arguments.of("tree", (Endpoint) prefix -> get(prefix + "/tree")),
                Arguments.of("list", (Endpoint) prefix -> get(prefix)),
                Arguments.of("get", (Endpoint) prefix -> get(prefix + "/9")),
                Arguments.of("leaves", (Endpoint) prefix -> get(prefix + "/leaves")),
                Arguments.of("schedule", (Endpoint) prefix -> get(prefix + "/schedule"))));
    }

    static Stream<Arguments> writes() {
        return twins(Stream.of(
                Arguments.of("create", (Endpoint) prefix -> json(post(prefix), CREATE)),
                Arguments.of("bulk", (Endpoint) prefix -> json(post(prefix + "/bulk"), "{\"elements\":[]}")),
                Arguments.of("update", (Endpoint) prefix -> json(put(prefix + "/9"), "{\"title\":\"Footings\"}")),
                Arguments.of("delete", (Endpoint) prefix -> delete(prefix + "/9")),
                Arguments.of("move", (Endpoint) prefix -> json(post(prefix + "/9/move"), "{}")),
                Arguments.of("recalculate", (Endpoint) prefix -> post(prefix + "/9/recalculate")),
                Arguments.of("addDependency", (Endpoint) prefix -> json(post(prefix + "/dependencies"), LINK)),
                Arguments.of("removeDependency", (Endpoint) prefix -> delete(prefix + "/dependencies/3"))));
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
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().is2xxSuccessful());
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("writes")
    void aWriteIsForbiddenForAMemberWithoutTheRole(String name, String prefix, Endpoint endpoint) throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("writes")
    void aWriteIsAllowedForTheRole(String name, String prefix, Endpoint endpoint) throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().is2xxSuccessful());
    }

    private static Stream<Arguments> twins(Stream<Arguments> endpoints) {
        return endpoints.flatMap(args -> Stream.of(
                Arguments.of(args.get()[0], MOBILE, args.get()[1]),
                Arguments.of(args.get()[0], WEB, args.get()[1])));
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
