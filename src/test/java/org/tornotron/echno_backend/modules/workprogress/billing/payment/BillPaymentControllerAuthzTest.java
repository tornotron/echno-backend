package org.tornotron.echno_backend.modules.workprogress.billing.payment;

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
import org.tornotron.echno_backend.modules.workprogress.billing.payment.web.ContractBillPaymentController;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.web.ContractBillPaymentControllerWeb;

/**
 * The guard on every payment handler of both twins, at the slice: reading needs progress-bills:read,
 * which every member holds through the baseline; raising a payment needs progress-bills:approve and
 * naming a project's authorizer progress-bills:edit, which the system admin and project manager hold;
 * the steps after raising need only progress-bills:read, because the service holds them for the
 * project's named authorizer. On each prefix so the twins cannot drift. That each role holds exactly
 * what it did is {@code WorkProgressAccessEquivalenceIT}'s to show.
 */
@WebMvcTest({ContractBillPaymentController.class, ContractBillPaymentControllerWeb.class})
@Import(BillPaymentControllerAuthzTest.TestSecurityConfig.class)
class BillPaymentControllerAuthzTest {

    private static final String MOBILE = "/api/v1/contract-billing";
    private static final String WEB = "/api/v1/contract-billing/web";
    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Autowired
    private MockMvc mockMvc;

    // Named to match the @access bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "access")
    private AccessGuard access;

    /** The baseline's actions: every member reads. */
    private static final String[] MEMBER = {"progress-bills:read"};
    /** The system admin's and project manager's: the baseline plus every change. */
    private static final String[] PAIR = {"progress-bills:read", "progress-bills:edit", "progress-bills:approve"};

    /** Stubs {@code @access} to allow exactly these area:action keys; every other check answers false. */
    private void holds(String... permissions) {
        for (String permission : permissions) {
            String[] parts = permission.split(":");
            when(access.can(parts[0], parts[1])).thenReturn(true);
        }
    }

    @MockitoBean
    private BillPaymentService payments;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    static Stream<String> prefixes() {
        return Stream.of(MOBILE, WEB);
    }

    /** Writes a role gates: raising a payment, and naming or clearing a project's authorizer. */
    static Stream<Arguments> roleWrites() {
        return Stream.<Endpoint>of(
                        prefix -> json(post(prefix + "/bills/" + ID + "/payments"), "{\"amount\":100}"),
                        prefix -> json(put(prefix + "/projects/7/payment-authorizer"), "{\"employeeId\":3}"),
                        prefix -> delete(prefix + "/projects/7/payment-authorizer"))
                .flatMap(endpoint -> prefixes().map(prefix -> Arguments.of(prefix, endpoint)));
    }

    /**
     * The steps after raising. No role grants them: the project's payment authorizer does, which the
     * service checks per project, so here they need the baseline's read and nothing more.
     */
    static Stream<Arguments> authorizerSteps() {
        return Stream.<Endpoint>of(
                        prefix -> post(prefix + "/bill-payments/" + ID + "/authorize"),
                        prefix -> json(post(prefix + "/bill-payments/" + ID + "/reject"), "{\"text\":\"No\"}"),
                        prefix -> json(post(prefix + "/bill-payments/" + ID + "/record"),
                                "{\"bankReference\":\"UTR-1\",\"paidOn\":\"2026-11-08\"}"),
                        prefix -> post(prefix + "/bill-payments/" + ID + "/sync"))
                .flatMap(endpoint -> prefixes().map(prefix -> Arguments.of(prefix, endpoint)));
    }

    @ParameterizedTest
    @MethodSource("prefixes")
    void readingIsForMembers(String prefix) throws Exception {
        for (String path : new String[]{"/bills/" + ID + "/payments", "/projects/7/payment-authorizer"}) {
            mockMvc.perform(get(prefix + path).with(member())).andExpect(status().isForbidden());
            holds(MEMBER);
            mockMvc.perform(get(prefix + path).with(member())).andExpect(status().is2xxSuccessful());
        }
    }

    @ParameterizedTest
    @MethodSource("roleWrites")
    void aWriteIsForbiddenWithoutTheAction(String prefix, Endpoint endpoint) throws Exception {
        holds(MEMBER);
        mockMvc.perform(endpoint.on(prefix).with(member())).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("roleWrites")
    void aWriteIsAllowedWithTheAction(String prefix, Endpoint endpoint) throws Exception {
        holds(PAIR);
        mockMvc.perform(endpoint.on(prefix).with(member())).andExpect(status().is2xxSuccessful());
    }

    @ParameterizedTest
    @MethodSource("authorizerSteps")
    void anAuthorizerStepIsForbiddenOutsideTheTenant(String prefix, Endpoint endpoint) throws Exception {
        holds("progress-bills:edit", "progress-bills:approve");
        mockMvc.perform(endpoint.on(prefix).with(member())).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("authorizerSteps")
    void anAuthorizerStepReachesTheServiceForAMemberWithTheBaselineAlone(String prefix, Endpoint endpoint) throws Exception {
        holds(MEMBER);
        mockMvc.perform(endpoint.on(prefix).with(member())).andExpect(status().is2xxSuccessful());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor member() {
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
