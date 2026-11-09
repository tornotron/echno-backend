package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.AttendanceService;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.attendance.dto.AttendanceCheckInDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.attendance.validator.GeofenceValidator;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which shift a check-in is measured against when the employee has none of their own.
 *
 * <p>The attendance settings carry a default shift documented as the one to use "when a check-in
 * does not specify one", but nothing read it, so in a new organization a check-in for an employee
 * without a shift was refused even after an administrator had set the default.
 */
@ExtendWith(MockitoExtension.class)
class CheckInShiftFallbackTest {

    private static final Long ORG_ID = 100L;
    private static final Long EMPLOYEE_ID = 7L;
    private static final Long PROJECT_ID = 12L;

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
    @Mock private AttendanceSecurityService attendanceSecurity;

    private AttendanceService service;
    private Employee employee;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);
        service = new AttendanceService(attendanceRepository, shiftTimingRepository, employeeRepository,
                organizationRepository, projectRepository, settingsService, calculationService,
                sequenceValidator, attendanceMapper, attachmentService, fileStorageService,
                userContextService,
                new PayloadValidator(Validation.buildDefaultValidatorFactory().getValidator()),
                attendanceSecurity,
                new AttendanceGeofenceService(new GeofenceValidator()));

        Organization organization = new Organization();
        organization.setId(ORG_ID);
        employee = new Employee();
        employee.setId(EMPLOYEE_ID);
        employee.setEmployeeName("Meera Pillai");
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setProjectName("Lakeview Residency");
        project.setEmployees(new ArrayList<>());

        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
        when(employeeRepository.findByIdAndOrganizationId(EMPLOYEE_ID, ORG_ID))
                .thenReturn(Optional.of(employee));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID))
                .thenReturn(Optional.of(project));
        lenient().when(attendanceRepository.findByEmployeeIdAndAttendanceDateAndProjectId(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(attendanceRepository.save(any(Attendance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(attendanceMapper.toResponseDto(any(Attendance.class)))
                .thenReturn(AttendanceResponseDto.builder().build());
        lenient().when(attendanceSecurity.canRecordFor(EMPLOYEE_ID)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void givenSettingsWithDefaultShift(ShiftTiming defaultShift) {
        when(settingsService.resolveEffectiveSettings(ORG_ID, PROJECT_ID)).thenReturn(
                AttendanceSettings.builder()
                        .photoRequiredOnCheckIn(false)
                        .geolocationRequired(false)
                        .defaultShiftTiming(defaultShift)
                        .build());
    }

    private AttendanceCheckInDto checkIn() {
        AttendanceCheckInDto dto = new AttendanceCheckInDto();
        dto.setEmployeeId(EMPLOYEE_ID);
        dto.setProjectId(PROJECT_ID);
        dto.setEventTimestamp(LocalDateTime.of(2026, 11, 2, 9, 0));
        return dto;
    }

    private Attendance saved() {
        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void anEmployeeWithoutAShift_isMeasuredAgainstTheSettingsDefault() {
        ShiftTiming defaultShift = new ShiftTiming();
        givenSettingsWithDefaultShift(defaultShift);

        service.checkIn(checkIn(), null);

        assertThat(saved().getShiftTiming()).isSameAs(defaultShift);
        verify(calculationService).recalculate(any(Attendance.class), same(defaultShift));
    }

    @Test
    void theEmployeesOwnShift_stillWinsOverTheDefault() {
        ShiftTiming own = new ShiftTiming();
        employee.setShiftTiming(own);
        givenSettingsWithDefaultShift(new ShiftTiming());

        service.checkIn(checkIn(), null);

        assertThat(saved().getShiftTiming()).isSameAs(own);
    }

    @Test
    void withNoShiftAnywhere_theRefusalSaysWhoCanAssignOne() {
        givenSettingsWithDefaultShift(null);

        assertThatThrownBy(() -> service.checkIn(checkIn(), null))
                .isExactlyInstanceOf(ValidationException.class)
                .hasMessageContaining("Meera Pillai has no shift assigned")
                .hasMessageContaining("HR admin or system admin");
        verify(attendanceRepository, never()).save(any());
    }
}
