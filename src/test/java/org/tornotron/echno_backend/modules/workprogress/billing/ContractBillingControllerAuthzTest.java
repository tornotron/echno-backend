package org.tornotron.echno_backend.modules.workprogress.billing;

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
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillPdfService;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillService;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillingService;
import org.tornotron.echno_backend.modules.workprogress.billing.web.ContractBillingController;
import org.tornotron.echno_backend.modules.workprogress.billing.web.ContractBillingControllerWeb;

/**
 * The guard on every handler of both billing twins, at the slice: reads need membership of the
 * tenant, every write needs one of its roles, and each request is tried on both prefixes so the
 * twins cannot drift apart. Which roles each guard names is pinned in {@code WorkProgressModuleTest}.
 */
@WebMvcTest({ContractBillingController.class, ContractBillingControllerWeb.class})
@Import(ContractBillingControllerAuthzTest.TestSecurityConfig.class)
class ContractBillingControllerAuthzTest {

    private static final String MOBILE = "/api/v1/contract-billing";
    private static final String WEB = "/api/v1/contract-billing/web";
    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String BOQ = "{\"itemCode\":\"CP-01\",\"description\":\"RCC\",\"unit\":\"m3\",\"contractQuantity\":10,\"rate\":5}";
    private static final String RULE = "{\"kind\":\"RETENTION\",\"label\":\"Retention\",\"basis\":\"PERCENT\",\"rate\":5}";
    private static final String REQUIREMENT = "{\"title\":\"Cube tests\",\"type\":\"QUALITY_TEST\"}";
    private static final String CREATE = "{\"subContractId\":1,\"billingModel\":\"RUNNING_ACCOUNT\",\"periodFrom\":\"2026-08-01\",\"periodTo\":\"2026-08-31\"}";
    private static final String COMMENT = "{\"text\":\"Check CP-01\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private ContractBillingService billing;

    @MockitoBean
    private ContractBillService bills;

    @MockitoBean
    private ContractBillPdfService pdf;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    static Stream<Arguments> reads() {
        return twins(Stream.of(
                Arguments.of("overview", (Endpoint) prefix -> get(prefix + "/overview")),
                Arguments.of("contracts", (Endpoint) prefix -> get(prefix + "/contracts")),
                Arguments.of("contract", (Endpoint) prefix -> get(prefix + "/contracts/1")),
                Arguments.of("boq", (Endpoint) prefix -> get(prefix + "/contracts/1/boq-items")),
                Arguments.of("rules", (Endpoint) prefix -> get(prefix + "/contracts/1/deduction-rules")),
                Arguments.of("requirements", (Endpoint) prefix -> get(prefix + "/contracts/1/milestones/2/requirements")),
                Arguments.of("bills", (Endpoint) prefix -> get(prefix + "/bills")),
                Arguments.of("bill", (Endpoint) prefix -> get(prefix + "/bills/" + ID)),
                Arguments.of("events", (Endpoint) prefix -> get(prefix + "/bills/" + ID + "/events")),
                Arguments.of("documents", (Endpoint) prefix -> get(prefix + "/bills/" + ID + "/documents"))));
    }

    static Stream<Arguments> writes() {
        return twins(Stream.of(
                Arguments.of("add boq", (Endpoint) prefix -> json(post(prefix + "/contracts/1/boq-items"), BOQ)),
                Arguments.of("update boq", (Endpoint) prefix -> json(put(prefix + "/boq-items/" + ID), BOQ)),
                Arguments.of("delete boq", (Endpoint) prefix -> delete(prefix + "/boq-items/" + ID)),
                Arguments.of("add rule", (Endpoint) prefix -> json(post(prefix + "/contracts/1/deduction-rules"), RULE)),
                Arguments.of("update rule", (Endpoint) prefix -> json(put(prefix + "/deduction-rules/" + ID), RULE)),
                Arguments.of("delete rule", (Endpoint) prefix -> delete(prefix + "/deduction-rules/" + ID)),
                Arguments.of("add requirement", (Endpoint) prefix -> json(post(prefix + "/contracts/1/milestones/2/requirements"), REQUIREMENT)),
                Arguments.of("update requirement", (Endpoint) prefix -> json(put(prefix + "/requirements/" + ID), REQUIREMENT)),
                Arguments.of("delete requirement", (Endpoint) prefix -> delete(prefix + "/requirements/" + ID)),
                Arguments.of("create bill", (Endpoint) prefix -> json(post(prefix + "/bills"), CREATE)),
                Arguments.of("update bill", (Endpoint) prefix -> json(put(prefix + "/bills/" + ID), "{}")),
                Arguments.of("submit", (Endpoint) prefix -> post(prefix + "/bills/" + ID + "/submit")),
                Arguments.of("cancel", (Endpoint) prefix -> post(prefix + "/bills/" + ID + "/cancel")),
                Arguments.of("measurement", (Endpoint) prefix -> json(put(prefix + "/bills/" + ID + "/measurement"), "{}")),
                Arguments.of("verify", (Endpoint) prefix -> post(prefix + "/bills/" + ID + "/verify")),
                Arguments.of("return", (Endpoint) prefix -> json(post(prefix + "/bills/" + ID + "/return"), COMMENT)),
                Arguments.of("adjustments", (Endpoint) prefix -> json(put(prefix + "/bills/" + ID + "/adjustments"), "{\"adjustments\":[]}")),
                Arguments.of("certify", (Endpoint) prefix -> post(prefix + "/bills/" + ID + "/certify")),
                Arguments.of("approve", (Endpoint) prefix -> post(prefix + "/bills/" + ID + "/approve")),
                Arguments.of("note", (Endpoint) prefix -> json(post(prefix + "/bills/" + ID + "/notes"), COMMENT)),
                Arguments.of("presign", (Endpoint) prefix -> json(post(prefix + "/bills/" + ID + "/documents/presign"), "[]")),
                Arguments.of("register", (Endpoint) prefix -> json(post(prefix + "/bills/" + ID + "/documents/register"), "[]")),
                Arguments.of("delete document", (Endpoint) prefix -> delete(prefix + "/bills/" + ID + "/documents/9"))));
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
