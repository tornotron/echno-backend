package org.tornotron.echno_backend.attendance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.attendance.dto.AttendanceCheckInDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceClockEventDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How a supervisor's refused entry reaches the client, on both attendance twins (#839).
 *
 * <p>The mobile and web controllers are two copies of the same endpoints under two prefixes, and
 * they have to answer alike: the same guard, and the same 422 carrying the distance when the
 * supervisor is outside the site. Each case therefore runs once per prefix, so a change that
 * lands on one twin and not the other fails here.
 *
 * <p>The rule itself is pinned by {@code TeamMarkingRuleTest}; here the service is a mock that
 * raises the refusal, and what is under test is the status and body the exception handler turns
 * it into, and that the guard still admits a member and refuses an outsider.
 */
@WebMvcTest({AttendanceController.class, AttendanceControllerWeb.class})
@Import(TeamMarkingEndpointTest.TestSecurityConfig.class)
class TeamMarkingEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttendanceService attendanceService;

    @MockitoBean
    private JsonPartBinder jsonPartBinder;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here because
    // .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    /**
     * The check-in and clock-event endpoints read their JSON from a form field named {@code data}
     * beside an optional photo part, so the request is a multipart form with a parameter, not a
     * second file.
     */
    private static MockHttpServletRequestBuilder markingRequest(String path) {
        return multipart(path).param("data", "{}").with(jwt());
    }

    private void callerIsAMember() {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
    }

    @ParameterizedTest(name = "{0}/check-in")
    @ValueSource(strings = {"/api/v1/attendance", "/api/v1/attendance/web"})
    void aSupervisorOutsideTheFence_isRefusedWithTheDistance_onCheckIn(String prefix) throws Exception {
        callerIsAMember();
        when(jsonPartBinder.read(any(), eq(AttendanceCheckInDto.class))).thenReturn(new AttendanceCheckInDto());
        when(attendanceService.checkIn(any(), any()))
                .thenThrow(new TeamMarkingOutsideGeofenceException(256.4, 100));

        mockMvc.perform(markingRequest(prefix + "/check-in"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail", containsString("256 m")))
                .andExpect(jsonPath("$.detail", containsString("100 m site boundary")));
    }

    @ParameterizedTest(name = "{0}/clock-event")
    @ValueSource(strings = {"/api/v1/attendance", "/api/v1/attendance/web"})
    void aSupervisorOutsideTheFence_isRefusedWithTheDistance_onClockEvent(String prefix) throws Exception {
        callerIsAMember();
        when(jsonPartBinder.read(any(), eq(AttendanceClockEventDto.class))).thenReturn(new AttendanceClockEventDto());
        when(attendanceService.recordClockEvent(any(), any()))
                .thenThrow(new TeamMarkingOutsideGeofenceException(256.4, 100));

        mockMvc.perform(markingRequest(prefix + "/clock-event"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail", containsString("256 m")));
    }

    @ParameterizedTest(name = "{0}/check-in")
    @ValueSource(strings = {"/api/v1/attendance", "/api/v1/attendance/web"})
    void aSupervisorOnSite_isAnswered(String prefix) throws Exception {
        callerIsAMember();
        when(jsonPartBinder.read(any(), eq(AttendanceCheckInDto.class))).thenReturn(new AttendanceCheckInDto());
        when(attendanceService.checkIn(any(), any())).thenReturn(AttendanceResponseDto.builder().id(781L).build());

        mockMvc.perform(markingRequest(prefix + "/check-in"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(781));
    }

    @ParameterizedTest(name = "{0}/check-in")
    @ValueSource(strings = {"/api/v1/attendance", "/api/v1/attendance/web"})
    void anOutsider_isRefusedBeforeTheServiceIsReached(String prefix) throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);

        mockMvc.perform(markingRequest(prefix + "/check-in"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(attendanceService);
    }

    @Test
    void theRefusalMessageNamesBothNumbers() {
        // What the client shows verbatim, so the sentence has to carry the distance and the
        // radius the way the self-marking prompt does.
        TeamMarkingOutsideGeofenceException ex = new TeamMarkingOutsideGeofenceException(256.4, 100);
        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("256 m from the project site")
                .contains("100 m site boundary");
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
