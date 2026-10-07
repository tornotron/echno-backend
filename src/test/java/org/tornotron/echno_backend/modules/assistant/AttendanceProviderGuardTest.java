package org.tornotron.echno_backend.modules.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.tornotron.echno_backend.attendance.AttendanceService;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.provider.AttendanceProvider;

/**
 * The guard on {@code retrieve} actually stops a caller, not just sits on the method. Parity with
 * the controller is one claim; that method security applies to this bean, as the pipeline will
 * call it, is another, and a final class or a call that skipped the proxy would break it silently.
 */
@SpringJUnitConfig(AttendanceProviderGuardTest.Config.class)
class AttendanceProviderGuardTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        AttendanceService attendanceService() {
            return mock(AttendanceService.class);
        }

        @Bean("attendanceSecurity")
        AttendanceSecurityService attendanceSecurity() {
            return mock(AttendanceSecurityService.class);
        }

        @Bean
        AttendanceProvider attendanceProvider(AttendanceService service) {
            return new AttendanceProvider(service);
        }
    }

    // By its interface, as the registry receives it: Spring proxies it for method security.
    @Autowired
    private AssistantProvider provider;

    @Autowired
    private AttendanceService service;

    @Autowired
    private AttendanceSecurityService attendanceSecurity;

    private static final Scope SCOPE = new Scope(42L, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 10), null);

    @BeforeEach
    void authenticate() {
        reset(service, attendanceSecurity);
        // Three arguments: the two-argument constructor leaves the token unauthenticated.
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("caller", null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aCallerWhoCannotManageAttendanceRecordsIsRefusedBeforeAnythingIsRead() {
        when(attendanceSecurity.canManageRecords()).thenReturn(false);

        assertThatThrownBy(() -> provider.retrieve(new Question("who was on site?"), SCOPE))
                .isInstanceOf(AccessDeniedException.class);
        verify(service, never()).getAttendanceByProject(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aCallerWhoCanManageThemGetsTheAnswer() {
        when(attendanceSecurity.canManageRecords()).thenReturn(true);

        ProviderResult result = provider.retrieve(new Question("who was on site?"), SCOPE);

        assertThat(result).isInstanceOf(ProviderResult.Empty.class);
    }
}
