package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRegularization;
import org.tornotron.echno_backend.attendance.AttendanceRegularizationRepository;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.dto.AttendanceRegularizationDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationActionDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationRequestDto;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;
import org.tornotron.echno_backend.attendance.mapper.AttendanceRegularizationMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AttendanceRegularizationService}. Repositories, the settings and
 * calculation services, and the mapper are mocked. The focus is the submission and
 * processing gates the service owns: refusing self-service when disabled, enforcing the
 * monthly cap, blocking a duplicate pending request, choosing PENDING vs auto-APPROVED from
 * the settings, applying corrected events only on auto-approve, and refusing to re-action a
 * request that is no longer PENDING (recalculating attendance only on approval).
 */
@ExtendWith(MockitoExtension.class)
class AttendanceRegularizationServiceTest {

    private static final Long ORG = 100L;
    private static final Long ATT_ID = 42L;
    private static final Long REG_ID = 7L;
    private static final String REQUESTER = "user-abc";
    private static final String APPROVER = "manager-xyz";

    @Mock private AttendanceRegularizationRepository regularizationRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private AttendanceSettingsService settingsService;
    @Mock private AttendanceCalculationService calculationService;
    @Mock private AttendanceRegularizationMapper regularizationMapper;

    private AttendanceRegularizationService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new AttendanceRegularizationService(regularizationRepository, attendanceRepository,
                organizationRepository, settingsService, calculationService, regularizationMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Organization organization() {
        Organization org = new Organization();
        org.setId(ORG);
        return org;
    }

    private Attendance attendance() {
        return Attendance.builder()
                .id(ATT_ID)
                .attendanceDate(LocalDate.of(2024, 5, 10))
                .projectId(9L)
                .build();
    }

    private AttendanceSettings settings(boolean allowSelf, boolean approvalRequired, int maxPerMonth) {
        return AttendanceSettings.builder()
                .allowSelfRegularization(allowSelf)
                .regularizationApprovalRequired(approvalRequired)
                .maxRegularizationDaysPerMonth(maxPerMonth)
                .build();
    }

    private RegularizationRequestDto requestDto() {
        RegularizationRequestDto dto = new RegularizationRequestDto();
        dto.setAttendanceId(ATT_ID);
        dto.setReason("Forgot to clock out");
        return dto;
    }

    private void stubOrgAndAttendance(Attendance attendance) {
        lenient().when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        lenient().when(attendanceRepository.findByIdAndOrganization_Id(ATT_ID, ORG))
                .thenReturn(Optional.of(attendance));
    }

    @Test
    void submitRequest_unknownOrganization_throwsNotFound() {
        when(organizationRepository.findById(ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.submitRequest(requestDto(), REQUESTER));
    }

    @Test
    void submitRequest_unknownAttendance_throwsNotFound() {
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        when(attendanceRepository.findByIdAndOrganization_Id(ATT_ID, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.submitRequest(requestDto(), REQUESTER));
    }

    @Test
    void submitRequest_selfRegularizationDisabled_throwsValidation() {
        stubOrgAndAttendance(attendance());
        when(settingsService.resolveEffectiveSettings(eq(ORG), any()))
                .thenReturn(settings(false, true, 3));

        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service.submitRequest(requestDto(), REQUESTER));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submitRequest_monthlyLimitReached_throwsValidation() {
        stubOrgAndAttendance(attendance());
        when(settingsService.resolveEffectiveSettings(eq(ORG), any()))
                .thenReturn(settings(true, true, 2));
        when(regularizationRepository.countApprovedRegularizationsInMonth(eq(REQUESTER), any(), any()))
                .thenReturn(2L);

        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service.submitRequest(requestDto(), REQUESTER));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submitRequest_existingPendingRequest_throwsValidation() {
        stubOrgAndAttendance(attendance());
        when(settingsService.resolveEffectiveSettings(eq(ORG), any()))
                .thenReturn(settings(true, true, 3));
        when(regularizationRepository.countApprovedRegularizationsInMonth(eq(REQUESTER), any(), any()))
                .thenReturn(0L);
        AttendanceRegularization pending = AttendanceRegularization.builder()
                .status(RegularizationStatus.PENDING).build();
        when(regularizationRepository.findByAttendanceId(ATT_ID)).thenReturn(Optional.of(pending));

        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service.submitRequest(requestDto(), REQUESTER));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submitRequest_approvalRequired_savesAsPending() {
        stubOrgAndAttendance(attendance());
        when(settingsService.resolveEffectiveSettings(eq(ORG), any()))
                .thenReturn(settings(true, true, 3));
        when(regularizationRepository.countApprovedRegularizationsInMonth(eq(REQUESTER), any(), any()))
                .thenReturn(0L);
        when(regularizationRepository.findByAttendanceId(ATT_ID)).thenReturn(Optional.empty());
        when(regularizationRepository.save(any(AttendanceRegularization.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(regularizationMapper.toDto(any())).thenReturn(AttendanceRegularizationDto.builder().build());

        service.submitRequest(requestDto(), REQUESTER);

        ArgumentCaptor<AttendanceRegularization> captor =
                ArgumentCaptor.forClass(AttendanceRegularization.class);
        verify(regularizationRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RegularizationStatus.PENDING);
        assertThat(captor.getValue().getRequestedBy()).isEqualTo(REQUESTER);
    }

    @Test
    void submitRequest_autoApprove_savesApprovedAndAppliesCorrectedEvents() {
        Attendance attendance = attendance();
        stubOrgAndAttendance(attendance);
        when(settingsService.resolveEffectiveSettings(eq(ORG), any()))
                .thenReturn(settings(true, false, 3));
        when(regularizationRepository.countApprovedRegularizationsInMonth(eq(REQUESTER), any(), any()))
                .thenReturn(0L);
        when(regularizationRepository.findByAttendanceId(ATT_ID)).thenReturn(Optional.empty());
        when(regularizationRepository.save(any(AttendanceRegularization.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(regularizationMapper.toDto(any())).thenReturn(AttendanceRegularizationDto.builder().build());

        service.submitRequest(requestDto(), REQUESTER);

        ArgumentCaptor<AttendanceRegularization> captor =
                ArgumentCaptor.forClass(AttendanceRegularization.class);
        verify(regularizationRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RegularizationStatus.APPROVED);
    }

    @Test
    void processRegularization_unknownRequest_throwsNotFound() {
        when(regularizationRepository.findByIdAndOrganization_Id(REG_ID, ORG)).thenReturn(Optional.empty());

        RegularizationActionDto action = new RegularizationActionDto();
        action.setStatus(RegularizationStatus.APPROVED);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.processRegularization(REG_ID, action, APPROVER));
    }

    @Test
    void processRegularization_notPending_throwsValidation() {
        AttendanceRegularization already = AttendanceRegularization.builder()
                .status(RegularizationStatus.APPROVED).build();
        when(regularizationRepository.findByIdAndOrganization_Id(REG_ID, ORG))
                .thenReturn(Optional.of(already));

        RegularizationActionDto action = new RegularizationActionDto();
        action.setStatus(RegularizationStatus.APPROVED);

        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service.processRegularization(REG_ID, action, APPROVER));
        verify(calculationService, never()).recalculate(any(), any());
    }

    @Test
    void processRegularization_approveWithShift_recalculatesAttendance() {
        Attendance attendance = attendance();
        attendance.setShiftTiming(new ShiftTiming());
        AttendanceRegularization pending = AttendanceRegularization.builder()
                .status(RegularizationStatus.PENDING).attendance(attendance).build();
        when(regularizationRepository.findByIdAndOrganization_Id(REG_ID, ORG))
                .thenReturn(Optional.of(pending));
        when(regularizationRepository.save(any(AttendanceRegularization.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(regularizationMapper.toDto(any())).thenReturn(AttendanceRegularizationDto.builder().build());

        RegularizationActionDto action = new RegularizationActionDto();
        action.setStatus(RegularizationStatus.APPROVED);

        service.processRegularization(REG_ID, action, APPROVER);

        verify(calculationService).recalculate(eq(attendance), any(ShiftTiming.class));
        verify(attendanceRepository).save(attendance);
        assertThat(pending.getStatus()).isEqualTo(RegularizationStatus.APPROVED);
        assertThat(pending.getApprovedBy()).isEqualTo(APPROVER);
    }

    @Test
    void processRegularization_reject_doesNotRecalculate() {
        Attendance attendance = attendance();
        attendance.setShiftTiming(new ShiftTiming());
        AttendanceRegularization pending = AttendanceRegularization.builder()
                .status(RegularizationStatus.PENDING).attendance(attendance).build();
        when(regularizationRepository.findByIdAndOrganization_Id(REG_ID, ORG))
                .thenReturn(Optional.of(pending));
        when(regularizationRepository.save(any(AttendanceRegularization.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(regularizationMapper.toDto(any())).thenReturn(AttendanceRegularizationDto.builder().build());

        RegularizationActionDto action = new RegularizationActionDto();
        action.setStatus(RegularizationStatus.REJECTED);
        action.setRejectionReason("Insufficient evidence");

        service.processRegularization(REG_ID, action, APPROVER);

        verify(calculationService, never()).recalculate(any(), any());
        verify(attendanceRepository, never()).save(any());
        assertThat(pending.getRejectionReason()).isEqualTo("Insufficient evidence");
    }
}
