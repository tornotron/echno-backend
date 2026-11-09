package org.tornotron.echno_backend.risk;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;

/**
 * The guard on every handler of both risk register twins, at the slice: reads need compliance:read,
 * which every member holds through the baseline, writes need the compliance action they make, which
 * the system admin and project manager hold, and each request is tried on both prefixes so the twins
 * cannot drift apart. That each role holds exactly what it did is
 * {@code ComplianceAccessEquivalenceIT}'s to show.
 */
@WebMvcTest({ProjectRiskController.class, ProjectRiskControllerWeb.class})
@Import(ProjectRiskControllerAuthzTest.TestSecurityConfig.class)
class ProjectRiskControllerAuthzTest {

    private static final String MOBILE = "/api/v1/project/42/risks";
    private static final String WEB = "/api/v1/project/42/risks/web";
    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String RISK = "{\"title\":\"Late drawings\",\"category\":\"design-engineering\","
            + "\"status\":\"identified\",\"probability\":\"medium\",\"impact\":\"major\","
            + "\"residualProbability\":\"low\",\"residualImpact\":\"minor\",\"responseType\":\"mitigate\"}";

    @Autowired
    private MockMvc mockMvc;

    // Named to match the @access bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "access")
    private AccessGuard access;

    /** The baseline's compliance action: every member reads the register. */
    private static final String[] MEMBER = {"compliance:read"};
    /** The system admin's and project manager's: the baseline plus every write. */
    private static final String[] PAIR = {"compliance:read", "compliance:create", "compliance:edit", "compliance:delete"};

    /** Stubs {@code @access} to allow exactly these area:action keys; every other check answers false. */
    private void holds(String... permissions) {
        for (String permission : permissions) {
            String[] parts = permission.split(":");
            when(access.can(parts[0], parts[1])).thenReturn(true);
        }
    }

    @MockitoBean
    private ProjectRiskService service;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    static Stream<Arguments> reads() {
        return twins(Stream.of(
                Arguments.of("list", (Endpoint) prefix -> get(prefix)),
                Arguments.of("get", (Endpoint) prefix -> get(prefix + "/" + ID))));
    }

    static Stream<Arguments> writes() {
        return twins(Stream.of(
                Arguments.of("create", (Endpoint) prefix -> json(post(prefix), RISK)),
                Arguments.of("import", (Endpoint) prefix -> json(post(prefix + "/import"), "{\"risks\":[" + RISK + "]}")),
                Arguments.of("update", (Endpoint) prefix -> json(put(prefix + "/" + ID), RISK)),
                Arguments.of("delete", (Endpoint) prefix -> delete(prefix + "/" + ID))));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("reads")
    void aReadIsForbiddenForANonMember(String name, String prefix, Endpoint endpoint) throws Exception {
        holds();
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("reads")
    void aReadIsOkForAMember(String name, String prefix, Endpoint endpoint) throws Exception {
        holds(MEMBER);
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().is2xxSuccessful());
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("writes")
    void aWriteIsForbiddenForAMemberWithoutTheRole(String name, String prefix, Endpoint endpoint) throws Exception {
        holds(MEMBER);
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("writes")
    void aWriteIsAllowedForTheRole(String name, String prefix, Endpoint endpoint) throws Exception {
        holds(PAIR);
        mockMvc.perform(endpoint.on(prefix).with(memberOfOrgSeven())).andExpect(status().is2xxSuccessful());
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("invalidBodies")
    void aBodyOutsideTheVocabularyIsRefused(String name, String prefix, String body) throws Exception {
        holds(PAIR);
        mockMvc.perform(json(post(prefix), body).with(memberOfOrgSeven())).andExpect(status().isBadRequest());
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                        Arguments.of("unknown category", RISK.replace("design-engineering", "weather")),
                        Arguments.of("unknown probability", RISK.replace("\"medium\"", "\"likely\"")),
                        Arguments.of("blank title", RISK.replace("Late drawings", " ")))
                .flatMap(args -> Stream.of(
                        Arguments.of(args.get()[0], MOBILE, args.get()[1]),
                        Arguments.of(args.get()[0], WEB, args.get()[1])));
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
