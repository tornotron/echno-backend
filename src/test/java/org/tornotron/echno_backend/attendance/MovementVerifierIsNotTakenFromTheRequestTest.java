package org.tornotron.echno_backend.attendance;

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
import org.tornotron.echno_backend.attendance.service.MovementRecordService;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins that nothing a caller sends can name the verifier of a movement record.
 *
 * <p>The defect was not a loose check on the value, it was that the value was bound at all:
 * {@code verifiedBy} was a required {@code @RequestParam} on both verify handlers and was written
 * onto the row as sent, so the person a movement record named as its verifier was whatever the
 * client typed. Unlike the payment voucher this was reachable through the product, because the web
 * client did send it.
 *
 * <p>The requests below therefore carry {@code ?verifiedBy=Mallory Cheatham} on the wire, the way
 * the defect was reachable, and the assertion reads the arguments the controller handed the
 * service off the Mockito invocation rather than naming a method signature. That is deliberate: an
 * assertion written against the one-argument signature would only compile after the fix, so the
 * test would pass by not existing rather than by the fix. Read this way it is red on the old code,
 * where the arguments are {@code [7, "Mallory Cheatham"]}.
 *
 * <p>The status assertions are the deploy-safety evidence. A query parameter the handler no longer
 * declares is not a validation failure in Spring MVC, it is simply not bound, so the deployed
 * client's request still succeeds and its name is discarded; and a request that omits the
 * parameter, which the old code rejected outright because the parameter was required, succeeds
 * too. The backend can therefore ship before the client changes.
 *
 * <p>The counterpart to {@code MovementVerifyActionTest}, which covers what the action stamps.
 */
@WebMvcTest({MovementRecordControllerWeb.class, MovementRecordController.class})
@Import(MovementVerifierIsNotTakenFromTheRequestTest.TestSecurityConfig.class)
class MovementVerifierIsNotTakenFromTheRequestTest {

    private static final long MOVEMENT_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MovementRecordService service;

    @MockitoBean(name = "attendanceSecurity")
    private AttendanceSecurityService attendanceSecurity;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void aVerifyNamingSomebodyElse_isAcceptedAndTheNameNeverReachesTheService() throws Exception {
        when(attendanceSecurity.canManageRecords()).thenReturn(true);

        mockMvc.perform(post("/api/v1/movement-records/web/{id}/verify", MOVEMENT_ID)
                        .with(jwt())
                        .param("verifiedBy", "Mallory Cheatham"))
                .andExpect(status().isOk());

        assertThat(argumentsOfTheVerifyCall()).containsExactly(MOVEMENT_ID);
    }

    @Test
    void theSameHoldsOnTheNonWebController() throws Exception {
        when(attendanceSecurity.canManageRecords()).thenReturn(true);

        mockMvc.perform(post("/api/v1/movement-records/{id}/verify", MOVEMENT_ID)
                        .with(jwt())
                        .param("verifiedBy", "Mallory Cheatham"))
                .andExpect(status().isOk());

        assertThat(argumentsOfTheVerifyCall()).containsExactly(MOVEMENT_ID);
    }

    @Test
    void aVerifyThatNamesNobodyIsAcceptedToo_soAClientCanStopSendingTheParameter() throws Exception {
        when(attendanceSecurity.canManageRecords()).thenReturn(true);

        mockMvc.perform(post("/api/v1/movement-records/web/{id}/verify", MOVEMENT_ID).with(jwt()))
                .andExpect(status().isOk());

        assertThat(argumentsOfTheVerifyCall()).containsExactly(MOVEMENT_ID);
    }

    @Test
    void verifyStaysGatedOnTheRecordManagementRole() throws Exception {
        when(attendanceSecurity.canManageRecords()).thenReturn(false);

        mockMvc.perform(post("/api/v1/movement-records/web/{id}/verify", MOVEMENT_ID)
                        .with(jwt())
                        .param("verifiedBy", "Mallory Cheatham"))
                .andExpect(status().isForbidden());

        assertThat(mockingDetails(service).getInvocations()).isEmpty();
    }

    /**
     * What the controller passed the service, read off the recorded invocation rather than
     * through a typed verification, so the assertion compiles against either signature.
     */
    private List<Object> argumentsOfTheVerifyCall() {
        return mockingDetails(service).getInvocations().stream()
                .filter(invocation -> "verifyMovement".equals(invocation.getMethod().getName()))
                .findFirst()
                .map(invocation -> List.of(invocation.getArguments()))
                .orElseThrow(() -> new AssertionError("the controller never called verifyMovement"));
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
