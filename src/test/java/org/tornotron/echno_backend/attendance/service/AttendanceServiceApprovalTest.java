package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.Validation;
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
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.attendance.dto.AttendanceApprovalDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.tornotron.echno_backend.common.payload.PayloadValidator;

/**
 * Unit tests for the approval attribution in {@link AttendanceService#approveAttendance}. The focus
 * is that an approval records the authenticated employee's id and name, rather than the former
 * hard-coded "system" string, and that it falls back safely to "system" with no id when there is
 * no authenticated employee (for example a system or scheduled job).
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceApprovalTest {

    private static final Long ORG = 100L;
    private static final Long ATT_ID = 42L;
    private static final Long USER_ID = 7L;
    private static final Long APPROVER_EMP_ID = 55L;
    private static final String APPROVER_NAME = "Anand Rajashekar";

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
        TenantContext.setCurrentOrgId(ORG);
        service = new AttendanceService(attendanceRepository, shiftTimingRepository, employeeRepository,
                organizationRepository, projectRepository, settingsService, calculationService,
                sequenceValidator, attendanceMapper, attachmentService, fileStorageService,
                userContextService,
                new PayloadValidator(Validation.buildDefaultValidatorFactory().getValidator()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AttendanceApprovalDto approvalDto() {
        AttendanceApprovalDto dto = new AttendanceApprovalDto();
        dto.setApprovalStatus(ApprovalStatus.APPROVED);
        return dto;
    }

    private Employee approver() {
        Employee employee = new Employee();
        employee.setId(APPROVER_EMP_ID);
        employee.setEmployeeName(APPROVER_NAME);
        return employee;
    }

    @Test
    void approvalIsAttributedToTheAuthenticatedEmployee() {
        Attendance record = Attendance.builder().id(ATT_ID).build();
        when(attendanceRepository.findByIdAndOrganization_Id(ATT_ID, ORG)).thenReturn(Optional.of(record));
        when(userContextService.getCurrentUserId()).thenReturn(USER_ID);
        when(employeeRepository.findByUserIdAndOrganizationId(USER_ID, ORG)).thenReturn(Optional.of(approver()));
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(attendanceMapper.toResponseDto(any(Attendance.class), any(FileStorageService.class)))
                .thenReturn(AttendanceResponseDto.builder().build());

        service.approveAttendance(ATT_ID, approvalDto());

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        org.mockito.Mockito.verify(attendanceRepository).save(captor.capture());
        Attendance saved = captor.getValue();

        assertThat(saved.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(saved.getApprovedBy()).isEqualTo(APPROVER_NAME);
        assertThat(saved.getApprovedById()).isEqualTo(APPROVER_EMP_ID);
        assertThat(saved.getApprovedBy()).isNotEqualTo("system");
    }

    @Test
    void approvalFallsBackToSystemWhenNoAuthenticatedEmployee() {
        Attendance record = Attendance.builder().id(ATT_ID).build();
        when(attendanceRepository.findByIdAndOrganization_Id(ATT_ID, ORG)).thenReturn(Optional.of(record));
        when(userContextService.getCurrentUserId()).thenReturn(null);
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(attendanceMapper.toResponseDto(any(Attendance.class), any(FileStorageService.class)))
                .thenReturn(AttendanceResponseDto.builder().build());

        service.approveAttendance(ATT_ID, approvalDto());

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        org.mockito.Mockito.verify(attendanceRepository).save(captor.capture());
        Attendance saved = captor.getValue();

        assertThat(saved.getApprovedBy()).isEqualTo("system");
        assertThat(saved.getApprovedById()).isNull();
    }
}
