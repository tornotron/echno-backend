package org.tornotron.echno_backend.attendance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tornotron.echno_backend.attendance.dto.AttendanceRegularizationDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationActionDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationRequestDto;
import org.tornotron.echno_backend.attendance.service.AttendanceRegularizationService;
import org.tornotron.echno_backend.common.exception.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What happens to a caller that still sends {@code requestedBy} and {@code approvedBy} on the query
 * string, proved through the real request pipeline.
 *
 * <p>Both parameters used to be how the requester and the approver were recorded, and removing them
 * is a contract change on parameters a request body's "unknown properties are ignored" rule does not
 * cover. The deployed web client sends them on every call, so what matters is that Spring drops a
 * query parameter no handler declares rather than refusing the request. That is what lets the fix
 * ship on its own instead of having to land with a frontend release.
 *
 * <p>Driven through {@code MockMvc} standalone, because the question is about the binding rather
 * than anything the service does: no Spring context is started, so this adds none to the cached set
 * the test JVM holds.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceRegularizationApproverParamTest {

    private static final String WEB = "/api/v1/attendance-regularizations/web";
    private static final String PLAIN = "/api/v1/attendance-regularizations";

    private static final String REQUEST_BODY =
            "{\"attendanceId\":42,\"reason\":\"Forgot to clock out\",\"missingEvents\":[\"EVENING_CLOCK_OUT\"]}";
    private static final String ACTION_BODY = "{\"status\":\"APPROVED\"}";

    @Mock
    private AttendanceRegularizationService regularizationService;

    private MockMvc mockMvc(Object controller) {
        when(regularizationService.submitRequest(any(RegularizationRequestDto.class)))
                .thenReturn(AttendanceRegularizationDto.builder().build());
        when(regularizationService.processRegularization(anyLong(), any(RegularizationActionDto.class)))
                .thenReturn(AttendanceRegularizationDto.builder().build());
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void theWebTwinIgnoresAnApprovedByTheCallerStillSends() throws Exception {
        mockMvc(new AttendanceRegularizationControllerWeb(regularizationService))
                .perform(post(WEB + "/7/process")
                        .param("approvedBy", "Somebody Else")
                        .param("approvedById", "99")
                        .contentType(APPLICATION_JSON)
                        .content(ACTION_BODY))
                .andExpect(status().isOk());

        verify(regularizationService).processRegularization(anyLong(), any(RegularizationActionDto.class));
    }

    @Test
    void theOtherTwinIgnoresAnApprovedByTheCallerStillSends() throws Exception {
        mockMvc(new AttendanceRegularizationController(regularizationService))
                .perform(post(PLAIN + "/7/process")
                        .param("approvedBy", "Somebody Else")
                        .param("approvedById", "99")
                        .contentType(APPLICATION_JSON)
                        .content(ACTION_BODY))
                .andExpect(status().isOk());

        verify(regularizationService).processRegularization(anyLong(), any(RegularizationActionDto.class));
    }

    @Test
    void theWebTwinIgnoresARequestedByTheCallerStillSends() throws Exception {
        mockMvc(new AttendanceRegularizationControllerWeb(regularizationService))
                .perform(post(WEB + "/request")
                        .param("requestedBy", "Somebody Else")
                        .param("requestedById", "99")
                        .contentType(APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isCreated());

        verify(regularizationService).submitRequest(any(RegularizationRequestDto.class));
    }

    @Test
    void theOtherTwinIgnoresARequestedByTheCallerStillSends() throws Exception {
        mockMvc(new AttendanceRegularizationController(regularizationService))
                .perform(post(PLAIN + "/request")
                        .param("requestedBy", "Somebody Else")
                        .param("requestedById", "99")
                        .contentType(APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isCreated());

        verify(regularizationService).submitRequest(any(RegularizationRequestDto.class));
    }

    /** A caller that has been updated and sends neither is served the same way. */
    @Test
    void aCallerThatSendsNeitherIsServedTheSame() throws Exception {
        mockMvc(new AttendanceRegularizationControllerWeb(regularizationService))
                .perform(post(WEB + "/7/process")
                        .contentType(APPLICATION_JSON)
                        .content(ACTION_BODY))
                .andExpect(status().isOk());

        verify(regularizationService).processRegularization(anyLong(), any(RegularizationActionDto.class));
    }
}
