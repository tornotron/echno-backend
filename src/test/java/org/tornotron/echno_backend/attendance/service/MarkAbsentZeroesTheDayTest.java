package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.AttendanceService;
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
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

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Marking someone absent must persist a day of zero minutes, not a day of nulls.
 *
 * <p>{@code markAbsent} is the one reachable write path that builds an {@link Attendance} without
 * a shift, so {@code AttendanceCalculationService.recalculate} never runs on the record: the
 * check-in path calls it unconditionally, and the two later call sites are both guarded on the
 * shift being non-null. Whatever the builder writes is therefore what the row keeps for good.
 *
 * <p>The record goes to the repository through the real service rather than being assembled in
 * the test, because the defect this guards against lives in the gap between the field
 * declarations and the builder. A fixture built any other way, in particular through the no-args
 * constructor, runs the initialisers itself and would pass with the defect still present.
 */
@ExtendWith(MockitoExtension.class)
class MarkAbsentZeroesTheDayTest {

    private static final Long ORG = 100L;
    private static final Long EMPLOYEE_ID = 42L;
    private static final Long PROJECT_ID = 12L;
    private static final LocalDate DATE = LocalDate.of(2026, 9, 7);

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
    @Mock private AttendanceGeofenceService geofenceService;

    private AttendanceService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new AttendanceService(attendanceRepository, shiftTimingRepository,
                employeeRepository, organizationRepository, projectRepository, settingsService,
                calculationService, sequenceValidator, attendanceMapper, attachmentService,
                fileStorageService, userContextService,
                new PayloadValidator(Validation.buildDefaultValidatorFactory().getValidator()),
                attendanceSecurity, geofenceService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("an absence is stored with zero minutes on every session field")
    void absenceIsStoredWithZeroedMinutes() {
        Organization org = new Organization();
        org.setId(ORG);
        Employee employee = new Employee();
        employee.setId(EMPLOYEE_ID);
        employee.setEmployeeName("Aneesh Johny");
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setProjectName("Slab pour, tower B");

        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(org));
        when(employeeRepository.findByIdAndOrganizationId(EMPLOYEE_ID, ORG))
                .thenReturn(Optional.of(employee));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_ID, ORG))
                .thenReturn(Optional.of(project));
        when(attendanceRepository.findByEmployeeIdAndAttendanceDateAndProjectId(
                EMPLOYEE_ID, DATE, PROJECT_ID)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.markAbsent(EMPLOYEE_ID, PROJECT_ID, DATE);

        ArgumentCaptor<Attendance> saved = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, atLeastOnce()).save(saved.capture());
        Attendance stored = saved.getAllValues().get(0);

        assertThat(stored.getShiftTiming())
                .as("the record has no shift, so nothing will ever recalculate it")
                .isNull();
        assertThat(stored.getTotalWorkMinutes()).isZero();
        assertThat(stored.getMorningSessionMinutes()).isZero();
        assertThat(stored.getAfternoonSessionMinutes()).isZero();
        assertThat(stored.getOvertimeMinutes()).isZero();
        assertThat(stored.getBreakDurationMinutes()).isZero();
    }
}
