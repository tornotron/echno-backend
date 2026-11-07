package org.tornotron.echno_backend.modules.workprogress.billing.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.web.ContractBillPaymentController;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.web.ContractBillPaymentControllerWeb;

/**
 * The guard on every payment handler of both twins, at the slice: reading needs membership of the
 * tenant and every write needs a role, on each prefix so the twins cannot drift. Which roles each guard
 * names is pinned in {@code WorkProgressModuleTest}.
 */
@WebMvcTest({ContractBillPaymentController.class, ContractBillPaymentControllerWeb.class})
@Import(BillPaymentControllerAuthzTest.TestSecurityConfig.class)
class BillPaymentControllerAuthzTest {

    private static final String MOBILE = "/api/v1/contract-billing";
    private static final String WEB = "/api/v1/contract-billing/web";
    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private BillPaymentService payments;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    static Stream<String> prefixes() {
        return Stream.of(MOBILE, WEB);
    }

    static Stream<Arguments> writes() {
        return Stream.<Endpoint>of(
                        prefix -> json(post(prefix + "/bills/" + ID + "/payments"), "{\"amount\":100}"),
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
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);
        mockMvc.perform(get(prefix + "/bills/" + ID + "/payments").with(member())).andExpect(status().isForbidden());
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        mockMvc.perform(get(prefix + "/bills/" + ID + "/payments").with(member())).andExpect(status().is2xxSuccessful());
    }

    @ParameterizedTest
    @MethodSource("writes")
    void aWriteIsForbiddenWithoutTheRole(String prefix, Endpoint endpoint) throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
        mockMvc.perform(endpoint.on(prefix).with(member())).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("writes")
    void aWriteIsAllowedWithTheRole(String prefix, Endpoint endpoint) throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
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
