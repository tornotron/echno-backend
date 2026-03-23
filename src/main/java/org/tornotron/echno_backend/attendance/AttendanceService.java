package org.tornotron.echno_backend.attendance;

import jakarta.validation.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.attendance.dto.*;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.attendance.service.AttendanceCalculationService;
import org.tornotron.echno_backend.attendance.service.AttendanceSettingsService;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final ShiftTimingRepository shiftTimingRepository;
    private final EmployeeRepository employeeRepository;
    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final AttendanceSettingsService settingsService;
    private final AttendanceCalculationService calculationService;
    private final ClockEventSequenceValidator sequenceValidator;
    private final AttendanceMapper attendanceMapper;

    public AttendanceService(AttendanceRepository attendanceRepository,
                             ShiftTimingRepository shiftTimingRepository,
                             EmployeeRepository employeeRepository,
                             OrganizationRepository organizationRepository,
                             ProjectRepository projectRepository,
                             AttendanceSettingsService settingsService,
                             AttendanceCalculationService calculationService,
                             ClockEventSequenceValidator sequenceValidator,
                             AttendanceMapper attendanceMapper) {
        this.attendanceRepository = attendanceRepository;
        this.shiftTimingRepository = shiftTimingRepository;
        this.employeeRepository = employeeRepository;
        this.organizationRepository = organizationRepository;
        this.projectRepository = projectRepository;
        this.settingsService = settingsService;
        this.calculationService = calculationService;
        this.sequenceValidator = sequenceValidator;
        this.attendanceMapper = attendanceMapper;
    }

    @Transactional
    public AttendanceResponseDto checkIn(AttendanceCheckInDto dto) {
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        Employee employee = employeeRepository.findByIdAndOrganizationId(dto.getEmployeeId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        Project project = projectRepository.findByIdAndOrganization_Id(dto.getProjectId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        ShiftTiming shift = shiftTimingRepository.findByIdAndOrganization_Id(dto.getShiftTimingId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Shift timing not found"));

        AttendanceSettings settings = settingsService.resolveEffectiveSettings(orgId, dto.getProjectId());

        // Validate geolocation
        if (settings.getGeolocationRequired()
                && (dto.getLatitude() == null || dto.getLongitude() == null)) {
            throw new ValidationException("Geolocation is required for attendance in this project");
        }

        // Validate photo
        if (settings.getPhotoRequiredOnCheckIn()
                && (dto.getPhotoUrl() == null || dto.getPhotoUrl().isBlank())) {
            throw new ValidationException("Photo is required for check-in");
        }

        LocalDate attendanceDate = dto.getEventTimestamp().toLocalDate();

        // Check if attendance already exists
        if (attendanceRepository.findByEmployeeIdAndAttendanceDateAndProjectId(
                employee.getId(), attendanceDate, project.getId()).isPresent()) {
            throw new ValidationException("Attendance record already exists for this employee/date/project");
        }

        Attendance attendance = Attendance.builder()
                .employeeId(employee.getId())
                .employeeName(employee.getEmployeeName())
                .attendanceDate(attendanceDate)
                .projectId(project.getId())
                .projectName(project.getProjectName())
                .status(AttendanceStatus.PENDING_REGULARIZATION)
                .shiftTiming(shift)
                .approvalStatus(ApprovalStatus.PENDING)
                .organization(org)
                .clockEvents(new ArrayList<>())
                .regularizations(new ArrayList<>())
                .movements(new ArrayList<>())
                .build();

        ClockEvent clockEvent = ClockEvent.builder()
                .attendance(attendance)
                .eventType(ClockEventType.MORNING_CLOCK_IN)
                .eventTimestamp(dto.getEventTimestamp())
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .gpsAccuracy(dto.getGpsAccuracy())
                .altitude(dto.getAltitude())
                .photoUrl(dto.getPhotoUrl())
                .projectId(project.getId())
                .projectName(project.getProjectName())
                .devicePlatform(dto.getDevicePlatform())
                .deviceId(dto.getDeviceId())
                .ipAddress(dto.getIpAddress())
                .isWithinGeofence(false)
                .distanceFromProject(0.0)
                .isRegularized(false)
                .remarks(dto.getRemarks())
                .organization(org)
                .build();

        attendance.getClockEvents().add(clockEvent);
        calculationService.recalculate(attendance, shift);

        return attendanceMapper.toResponseDto(attendanceRepository.save(attendance));
    }

    @Transactional
    public AttendanceResponseDto recordClockEvent(AttendanceClockEventDto dto) {
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(dto.getAttendanceId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record not found"));

        AttendanceSettings settings = settingsService.resolveEffectiveSettings(orgId, attendance.getProjectId());

        // Validate geolocation
        if (settings.getGeolocationRequired()
                && (dto.getLatitude() == null || dto.getLongitude() == null)) {
            throw new ValidationException("Geolocation is required for attendance in this project");
        }

        // Validate photo based on event type
        boolean photoRequired =
                (dto.getEventType() == ClockEventType.MORNING_CLOCK_IN && settings.getPhotoRequiredOnCheckIn()) ||
                (dto.getEventType() == ClockEventType.EVENING_CLOCK_OUT && settings.getPhotoRequiredOnCheckOut());

        if (photoRequired && (dto.getPhotoUrl() == null || dto.getPhotoUrl().isBlank())) {
            throw new ValidationException("Photo is required for this clock event");
        }

        // Validate sequence
        sequenceValidator.validate(dto.getEventType(), attendance, settings);

        ClockEvent clockEvent = ClockEvent.builder()
                .attendance(attendance)
                .eventType(dto.getEventType())
                .eventTimestamp(dto.getEventTimestamp())
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .gpsAccuracy(dto.getGpsAccuracy())
                .altitude(dto.getAltitude())
                .photoUrl(dto.getPhotoUrl())
                .projectId(attendance.getProjectId())
                .projectName(attendance.getProjectName())
                .devicePlatform(dto.getDevicePlatform())
                .deviceId(dto.getDeviceId())
                .ipAddress(dto.getIpAddress())
                .isWithinGeofence(false)
                .distanceFromProject(0.0)
                .isRegularized(false)
                .remarks(dto.getRemarks())
                .organization(org)
                .build();

        attendance.getClockEvents().add(clockEvent);

        ShiftTiming shift = attendance.getShiftTiming();
        if (shift != null) {
            calculationService.recalculate(attendance, shift);
        }

        return attendanceMapper.toResponseDto(attendanceRepository.save(attendance));
    }

    @Transactional(readOnly = true)
    public AttendanceResponseDto getAttendanceById(Long id) {
        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance not found"));
        return attendanceMapper.toResponseDto(attendance);
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponseDto> getAttendanceByEmployee(Long employeeId,
                                                                LocalDate startDate,
                                                                LocalDate endDate) {
        return attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(employeeId, startDate, endDate)
                .stream()
                .map(attendanceMapper::toResponseDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<AttendanceResponseDto> getAttendanceByProject(Long projectId,
                                                               LocalDate date,
                                                               AttendanceStatus status,
                                                               String search,
                                                               Pageable pageable) {
        return attendanceRepository
                .findWithFilters(projectId, date, status, search, pageable)
                .map(attendanceMapper::toResponseDto);
    }

    @Transactional
    public AttendanceResponseDto approveAttendance(Long attendanceId, AttendanceApprovalDto dto, String approvedBy) {
        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(attendanceId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance not found"));

        attendance.setApprovalStatus(dto.getApprovalStatus());
        attendance.setApprovedBy(approvedBy);
        attendance.setApprovedAt(LocalDateTime.now());
        if (dto.getRemarks() != null) {
            attendance.setRemarks(dto.getRemarks());
        }

        return attendanceMapper.toResponseDto(attendanceRepository.save(attendance));
    }

    @Transactional
    public AttendanceResponseDto markAbsent(Long employeeId, Long projectId, LocalDate date) {
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        Project project = projectRepository.findByIdAndOrganization_Id(projectId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        Attendance attendance = attendanceRepository
                .findByEmployeeIdAndAttendanceDateAndProjectId(employeeId, date, projectId)
                .orElseGet(() -> {
                    Attendance draft = Attendance.builder()
                            .employeeId(employee.getId())
                            .employeeName(employee.getEmployeeName())
                            .attendanceDate(date)
                            .projectId(project.getId())
                            .projectName(project.getProjectName())
                            .status(AttendanceStatus.ABSENT)
                            .approvalStatus(ApprovalStatus.APPROVED)
                            .organization(org)
                            .clockEvents(new ArrayList<>())
                            .regularizations(new ArrayList<>())
                            .movements(new ArrayList<>())
                            .build();
                    return attendanceRepository.save(draft);
                });

        attendance.setStatus(AttendanceStatus.ABSENT);
        attendance.setApprovalStatus(ApprovalStatus.APPROVED);

        return attendanceMapper.toResponseDto(attendanceRepository.save(attendance));
    }

    @Transactional
    public AttendanceResponseDto markLeave(Long employeeId, Long projectId, LocalDate date,
                                            Long leaveId, String leaveType) {
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        Project project = projectRepository.findByIdAndOrganization_Id(projectId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        Attendance attendance = attendanceRepository
                .findByEmployeeIdAndAttendanceDateAndProjectId(employeeId, date, projectId)
                .orElseGet(() -> {
                    Attendance draft = Attendance.builder()
                            .employeeId(employee.getId())
                            .employeeName(employee.getEmployeeName())
                            .attendanceDate(date)
                            .projectId(project.getId())
                            .projectName(project.getProjectName())
                            .status(AttendanceStatus.LEAVE)
                            .approvalStatus(ApprovalStatus.APPROVED)
                            .organization(org)
                            .clockEvents(new ArrayList<>())
                            .regularizations(new ArrayList<>())
                            .movements(new ArrayList<>())
                            .build();
                    return attendanceRepository.save(draft);
                });

        attendance.setStatus(AttendanceStatus.LEAVE);
        attendance.setLeaveId(leaveId);
        attendance.setLeaveType(leaveType);
        attendance.setApprovalStatus(ApprovalStatus.APPROVED);

        return attendanceMapper.toResponseDto(attendanceRepository.save(attendance));
    }

    @Transactional(readOnly = true)
    public AttendanceSummaryDto getMonthlySummary(Long employeeId, int month, int year) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());

        List<Attendance> records = attendanceRepository
                .findByEmployeeIdAndAttendanceDateBetween(employeeId, from, to);

        return calculationService.buildMonthlySummary(
                employeeId, employee.getEmployeeName(), records, month, year);
    }

    @Transactional
    public void deleteAttendance(Long attendanceId) {
        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(attendanceId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record not found"));
        attendanceRepository.delete(attendance);
    }
}
