package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.AttendanceService;
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.attendance.dto.AttendanceCheckInDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceClockEventDto;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * All four check-in and clock-event handlers take their payload as the JSON part of a multipart
 * request and deserialize it by hand, so Spring never binds a bean and never validates one and
 * the constraints on these payloads were decorative. These pin that the service runs them
 * itself, and that a rejected payload never reaches the repository.
 *
 * <p>A real Hibernate Validator with mocked collaborators: whether the constraints fire needs a
 * genuine validator, but no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class AttendancePayloadValidationTest {

    private static ValidatorFactory factory;

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private ShiftTimingRepository shiftTimingRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private AttendanceSettingsService settingsService;
    @Mock private AttendanceCalculationService calculationService;
    @Mock private ClockEventSequenceValidator sequenceValidator;
    @Mock private AttendanceMapper attendanceMapper;
    @Mock private AttachmentService attachmentService;
    @Mock private FileStorageService fileStorageService;
    @Mock private UserContextService userContextService;

    private AttendanceService service;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        service = new AttendanceService(attendanceRepository, shiftTimingRepository,
                employeeRepository, organizationRepository, projectRepository, settingsService,
                calculationService, sequenceValidator, attendanceMapper, attachmentService,
                fileStorageService, userContextService, validator);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private AttendanceCheckInDto checkInDto() {
        AttendanceCheckInDto dto = new AttendanceCheckInDto();
        dto.setEmployeeId(42L);
        dto.setProjectId(12L);
        dto.setEventTimestamp(LocalDateTime.of(2026, 1, 15, 9, 2));
        return dto;
    }

    private AttendanceClockEventDto clockEventDto() {
        AttendanceClockEventDto dto = new AttendanceClockEventDto();
        dto.setAttendanceId(781L);
        dto.setEventType(ClockEventType.LUNCH_BREAK_START);
        dto.setEventTimestamp(LocalDateTime.of(2026, 1, 15, 13, 0));
        return dto;
    }

    @Test
    void checkIn_rejectsAMissingEmployeeId_beforeTouchingTheRepository() {
        // A null id went into the lookup and came back as a 404 naming employee "null".
        AttendanceCheckInDto dto = checkInDto();
        dto.setEmployeeId(null);

        assertThatThrownBy(() -> service.checkIn(dto, null))
                .isInstanceOf(ConstraintViolationException.class);

        verify(attendanceRepository, never()).save(ArgumentMatchers.any());
        verify(organizationRepository, never()).findById(ArgumentMatchers.any());
    }

    @Test
    void checkIn_rejectsAMissingProjectId() {
        AttendanceCheckInDto dto = checkInDto();
        dto.setProjectId(null);

        assertThatThrownBy(() -> service.checkIn(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void checkIn_rejectsAMissingEventTimestamp() {
        AttendanceCheckInDto dto = checkInDto();
        dto.setEventTimestamp(null);

        assertThatThrownBy(() -> service.checkIn(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void recordClockEvent_rejectsAMissingAttendanceId_beforeTouchingTheRepository() {
        AttendanceClockEventDto dto = clockEventDto();
        dto.setAttendanceId(null);

        assertThatThrownBy(() -> service.recordClockEvent(dto, null))
                .isInstanceOf(ConstraintViolationException.class);

        verify(attendanceRepository, never()).save(ArgumentMatchers.any());
        verify(organizationRepository, never()).findById(ArgumentMatchers.any());
    }

    @Test
    void recordClockEvent_rejectsAMissingEventType() {
        AttendanceClockEventDto dto = clockEventDto();
        dto.setEventType(null);

        assertThatThrownBy(() -> service.recordClockEvent(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void recordClockEvent_rejectsAMissingEventTimestamp() {
        AttendanceClockEventDto dto = clockEventDto();
        dto.setEventTimestamp(null);

        assertThatThrownBy(() -> service.recordClockEvent(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void checkIn_leavesTheOptionalShiftAlone() {
        // The shift is documented as optional: absent, the employee's assigned shift is used.
        // Failing past validation means it reached the tenant lookup, which is mocked empty
        // here; what matters is that it is not a constraint failure.
        AttendanceCheckInDto dto = checkInDto();
        dto.setShiftTimingId(null);

        assertThatThrownBy(() -> service.checkIn(dto, null))
                .isNotInstanceOf(ConstraintViolationException.class);
    }
}
