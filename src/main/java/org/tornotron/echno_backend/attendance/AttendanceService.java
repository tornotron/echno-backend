package org.tornotron.echno_backend.attendance;

import org.tornotron.echno_backend.common.payload.PayloadValidator;
import jakarta.validation.ValidationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.attendance.dto.*;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.attendance.service.AttendanceCalculationService;
import org.tornotron.echno_backend.attendance.service.AttendanceSettingsService;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Records site attendance from clock events and derives each day's worked-hours and status.
 *
 * <p>Check-in opens the day's record and its first clock event; later punches append to it. Each
 * change runs {@link AttendanceCalculationService} to recompute totals and status against the
 * shift. Enforces per-project photo and geolocation requirements from the effective settings, one
 * record per employee/date/project, and clock-event ordering. Uploaded photos are cleaned from
 * storage if the transaction rolls back. Also marks absence and leave days and builds monthly summaries.
 */
@Service
public class AttendanceService {

    private static final String ATTENDANCE_FOLDER = "attendance";

    /** Fallback approver name stamped when no authenticated employee can be resolved (e.g. a system job). */
    private static final String SYSTEM_APPROVER = "system";

    private final AttendanceRepository attendanceRepository;
    private final ShiftTimingRepository shiftTimingRepository;
    private final EmployeeRepository employeeRepository;
    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final AttendanceSettingsService settingsService;
    private final AttendanceCalculationService calculationService;
    private final ClockEventSequenceValidator sequenceValidator;
    private final AttendanceMapper attendanceMapper;
    private final AttachmentService attachmentService;
    private final FileStorageService fileStorageService;
    private final UserContextService userContextService;
    private final PayloadValidator payloadValidator;
    private final AttendanceSecurityService attendanceSecurity;

    public AttendanceService(AttendanceRepository attendanceRepository,
                             ShiftTimingRepository shiftTimingRepository,
                             EmployeeRepository employeeRepository,
                             OrganizationRepository organizationRepository,
                             ProjectRepository projectRepository,
                             AttendanceSettingsService settingsService,
                             AttendanceCalculationService calculationService,
                             ClockEventSequenceValidator sequenceValidator,
                             AttendanceMapper attendanceMapper,
                             AttachmentService attachmentService,
                             FileStorageService fileStorageService,
                             UserContextService userContextService,
                             PayloadValidator payloadValidator,
                             AttendanceSecurityService attendanceSecurity) {
        this.attendanceRepository = attendanceRepository;
        this.shiftTimingRepository = shiftTimingRepository;
        this.employeeRepository = employeeRepository;
        this.organizationRepository = organizationRepository;
        this.projectRepository = projectRepository;
        this.settingsService = settingsService;
        this.calculationService = calculationService;
        this.sequenceValidator = sequenceValidator;
        this.attendanceMapper = attendanceMapper;
        this.attachmentService = attachmentService;
        this.fileStorageService = fileStorageService;
        this.userContextService = userContextService;
        this.payloadValidator = payloadValidator;
        this.attendanceSecurity = attendanceSecurity;
    }

    /**
     * Refuses the call unless the caller is the employee the attendance belongs to, or holds an
     * attendance record-management role.
     *
     * <p>This lives here rather than in the {@code @PreAuthorize} guard for the same reason the
     * leave family's check does ({@code LeaveRequestService}): the guard can only see what the
     * caller sent. A check-in names its employee inside the multipart {@code data} part, and a
     * clock event names an attendance record whose owner only the database knows, so the employee
     * id the annotation could read is an argument, not evidence. Left at tenant membership, any
     * member could fabricate a colleague's check-in or clock them out.
     *
     * @param employeeId The employee the record belongs to, read off the stored record where one
     *     exists rather than off the request.
     * @throws AccessDeniedException if the caller is neither that employee nor a record manager.
     */
    private void requireActorMayRecordFor(Long employeeId) {
        if (attendanceSecurity.canRecordFor(employeeId)) {
            return;
        }
        throw new AccessDeniedException(
                "Attendance can only be recorded for yourself, unless you hold an attendance "
                        + "record-management role");
    }

    /**
     * Opens a day's attendance record with a morning clock-in event.
     *
     * <p>Validates the effective per-project settings (photo required on check-in, geolocation
     * required) and rejects a duplicate record for the same employee, date, and project. Worked
     * totals and status are computed from the new event, and any check-in photo is attached and
     * scheduled for cleanup should the transaction roll back.
     *
     * @param dto The check-in details, including employee, project, shift, timestamp, and location.
     * @param photo The check-in photo, or {@code null} when none is supplied.
     * @return The created attendance record.
     * @throws ResourceNotFoundException if the organization, employee, project, or shift is not found.
     * @throws jakarta.validation.ValidationException if a required photo or location is missing, the photo is not an image, or a record already exists for the day.
     * @throws AccessDeniedException if the caller is neither the employee named nor a holder of an
     *     attendance record-management role.
     */
    @Transactional
    public AttendanceResponseDto checkIn(AttendanceCheckInDto dto, MultipartFile photo) {
        payloadValidator.requireValid(dto);
        requireActorMayRecordFor(dto.getEmployeeId());
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + orgId + " was not found"));

        Employee employee = employeeRepository.findByIdAndOrganizationId(dto.getEmployeeId(), orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + dto.getEmployeeId() + " was not found in this organization"));

        Project project = projectRepository.findByIdAndOrganization_Id(dto.getProjectId(), orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project with ID " + dto.getProjectId() + " was not found in this organization"));

        // Prefer the employee's assigned structured shift. Only when they have none
        // do we fall back to a shift id supplied on the request (the pre-unification
        // behavior). A check-in is rejected when neither source yields a shift.
        ShiftTiming shift = employee.getShiftTiming();
        if (shift == null) {
            if (dto.getShiftTimingId() == null) {
                throw new ValidationException(
                        "No shift timing is assigned to this employee and none was supplied on the request");
            }
            shift = shiftTimingRepository.findByIdAndOrganization_Id(dto.getShiftTimingId(), orgId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Shift timing with ID " + dto.getShiftTimingId() + " was not found in this organization"));
        }

        AttendanceSettings settings = settingsService.resolveEffectiveSettings(orgId, dto.getProjectId());

        boolean photoProvided = photo != null && !photo.isEmpty();

        if (settings.getPhotoRequiredOnCheckIn() && !photoProvided) {
            throw new ValidationException("A photo is required to check in for this project");
        }

        if (photoProvided && isNotImage(photo)) {
            throw new ValidationException("The check-in photo must be a valid image file");
        }

        if (settings.getGeolocationRequired()
                && (dto.getLatitude() == null || dto.getLongitude() == null)) {
            throw new ValidationException("Latitude and longitude are required to record attendance for this project");
        }

        LocalDate attendanceDate = dto.getEventTimestamp().toLocalDate();

        if (attendanceRepository.findByEmployeeIdAndAttendanceDateAndProjectId(
                employee.getId(), attendanceDate, project.getId()).isPresent()) {
            throw new ValidationException(
                    "An attendance record already exists for employee " + employee.getId()
                            + " on " + attendanceDate + " for project " + project.getId());
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

        Attendance savedAttendance = attendanceRepository.save(attendance);

        if (photoProvided) {
            Attachment clockEventAttachment = attachmentService.uploadAttachment(
                    photo, "CLOCK_EVENT_CHECK_IN", clockEvent.getId(), ATTENDANCE_FOLDER);
            registerStorageCleanupOnRollback(clockEventAttachment.getStorageKey());
            clockEventAttachment.setOrganization(org);
            clockEvent.addAttachment(clockEventAttachment);
        }

        return attendanceMapper.toResponseDto(savedAttendance,fileStorageService);
    }

    private void registerStorageCleanupOnRollback(String storageKey) {
        if (storageKey == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    fileStorageService.deleteFile(storageKey);
                }
            }
        });
    }

    private boolean isNotImage(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType == null || !contentType.startsWith("image/");
    }

    /**
     * Appends a clock event (lunch start/end or clock-out) to an existing attendance record.
     *
     * <p>Validates geolocation and the photo rules that apply to the event type, checks the event
     * is a legal next step in the punch sequence, then recomputes totals and status against the
     * record's shift. Any photo is attached and scheduled for cleanup on rollback.
     *
     * @param dto The clock event details, including the attendance ID, event type, timestamp, and location.
     * @param photo The event photo, or {@code null} when none is supplied.
     * @return The updated attendance record.
     * @throws ResourceNotFoundException if the organization or attendance record is not found.
     * @throws jakarta.validation.ValidationException if a required photo or location is missing, the photo is not an image, or the event breaks the allowed sequence.
     * @throws AccessDeniedException if the caller is neither the employee the record belongs to
     *     nor a holder of an attendance record-management role.
     */
    @Transactional
    public AttendanceResponseDto recordClockEvent(AttendanceClockEventDto dto, MultipartFile photo) {
        payloadValidator.requireValid(dto);
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(dto.getAttendanceId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Attendance record with ID " + dto.getAttendanceId() + " was not found"));

        requireActorMayRecordFor(attendance.getEmployeeId());

        AttendanceSettings settings = settingsService.resolveEffectiveSettings(orgId, attendance.getProjectId());

        if (settings.getGeolocationRequired()
                && (dto.getLatitude() == null || dto.getLongitude() == null)) {
            throw new ValidationException("Latitude and longitude are required to record attendance for this project");
        }

        boolean photoProvided = photo != null && !photo.isEmpty();

        boolean photoRequired =
                (dto.getEventType() == ClockEventType.MORNING_CLOCK_IN && settings.getPhotoRequiredOnCheckIn()) ||
                (dto.getEventType() == ClockEventType.EVENING_CLOCK_OUT && settings.getPhotoRequiredOnCheckOut());

        if (photoRequired && !photoProvided) {
            throw new ValidationException("A photo is required for this " + dto.getEventType() + " event");
        }

        if (photoProvided && isNotImage(photo)) {
            throw new ValidationException("The clock event photo must be a valid image file");
        }

        sequenceValidator.validate(dto.getEventType(), attendance, settings);

        ClockEvent clockEvent = ClockEvent.builder()
                .attendance(attendance)
                .eventType(dto.getEventType())
                .eventTimestamp(dto.getEventTimestamp())
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .gpsAccuracy(dto.getGpsAccuracy())
                .altitude(dto.getAltitude())
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

        Attendance savedAttendance = attendanceRepository.save(attendance);

        if (photoProvided) {
            String attachmentType = dto.getEventType() == ClockEventType.EVENING_CLOCK_OUT
                    ? "CLOCK_EVENT_CHECK_OUT"
                    : "CLOCK_EVENT_CHECK_IN";
            Attachment clockEventAttachment = attachmentService.uploadAttachment(
                    photo, attachmentType, clockEvent.getId(), ATTENDANCE_FOLDER);
            registerStorageCleanupOnRollback(clockEventAttachment.getStorageKey());
            clockEventAttachment.setOrganization(org);
            clockEvent.addAttachment(clockEventAttachment);
        }

        return attendanceMapper.toResponseDto(savedAttendance,fileStorageService);
    }

    /**
     * Retrieves a single attendance record by its ID.
     *
     * @param id The ID of the attendance record.
     * @return The attendance record.
     * @throws ResourceNotFoundException if no record with the given ID exists in this organization.
     */
    @Transactional(readOnly = true)
    public AttendanceResponseDto getAttendanceById(Long id) {
        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record with ID " + id + " was not found"));
        return attendanceMapper.toResponseDto(attendance,fileStorageService);
    }

    /**
     * Lists an employee's attendance records within a date range.
     *
     * @param employeeId The employee's ID.
     * @param startDate The inclusive start of the range.
     * @param endDate The inclusive end of the range.
     * @return The matching attendance records.
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponseDto> getAttendanceByEmployee(Long employeeId,
                                                                LocalDate startDate,
                                                                LocalDate endDate) {
        return attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(employeeId, startDate, endDate)
                .stream()
                .map(attendance -> attendanceMapper.toResponseDto(attendance,fileStorageService))
                .collect(Collectors.toList());
    }

    /**
     * Lists a project's attendance for a day, filtered by status and a name search.
     *
     * <p>A blank search matches all employees; otherwise it matches employee names
     * case-insensitively. Results are drawn from a single page and returned as a list.
     *
     * @param projectId The project's ID.
     * @param date The attendance date.
     * @param status The status to filter by, or {@code null} for any.
     * @param search An employee-name fragment, or {@code null}/blank for all.
     * @param pageable The pagination and sort parameters.
     * @return The matching attendance records for the page.
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponseDto> getAttendanceByProject(Long projectId,
                                                               LocalDate date,
                                                               AttendanceStatus status,
                                                               String search,
                                                               Pageable pageable) {
        String searchPattern = (search == null || search.isBlank())
                ? null
                : "%" + search.toLowerCase() + "%";
        return attendanceRepository
                .findWithFilters(projectId, date, status, searchPattern, pageable)
                .map(attendance -> attendanceMapper.toResponseDto(attendance,fileStorageService)).getContent();
    }

    /**
     * Sets the approval decision on an attendance record and stamps who approved it and when.
     *
     * <p>The approver is resolved from the security context: the authenticated user is mapped to
     * their {@link Employee} in the current organization, and that employee's id and name are
     * stamped onto the record. When no employee can be resolved for the caller (for example a
     * system or scheduled job that runs without a user principal), the record falls back to the
     * {@code "system"} name with no approver id.
     *
     * @param attendanceId The ID of the attendance record.
     * @param dto The approval status and optional remarks.
     * @return The updated attendance record.
     * @throws ResourceNotFoundException if no record with the given ID exists in this organization.
     */
    @Transactional
    public AttendanceResponseDto approveAttendance(Long attendanceId, AttendanceApprovalDto dto) {
        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(attendanceId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record with ID " + attendanceId + " was not found"));

        Employee approver = resolveCurrentEmployee();

        attendance.setApprovalStatus(dto.getApprovalStatus());
        if (approver != null) {
            attendance.setApprovedBy(approver.getEmployeeName());
            attendance.setApprovedById(approver.getId());
        } else {
            attendance.setApprovedBy(SYSTEM_APPROVER);
            attendance.setApprovedById(null);
        }
        attendance.setApprovedAt(LocalDateTime.now());
        if (dto.getRemarks() != null) {
            attendance.setRemarks(dto.getRemarks());
        }

        return attendanceMapper.toResponseDto(attendanceRepository.save(attendance),fileStorageService);
    }

    /**
     * Resolves the authenticated caller to their {@link Employee} in the current organization,
     * or {@code null} when there is no authenticated user or no matching employee record (for
     * example a system or scheduled job).
     */
    private Employee resolveCurrentEmployee() {
        Long userId = userContextService.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return employeeRepository
                .findByUserIdAndOrganizationId(userId, TenantContext.getCurrentOrgId())
                .orElse(null);
    }

    /**
     * Marks an employee absent for a day on a project, creating the record if none exists.
     *
     * <p>The resulting record is approved, since an absence marking is an administrative decision.
     *
     * @param employeeId The employee's ID.
     * @param projectId The project's ID.
     * @param date The attendance date.
     * @return The updated or created attendance record.
     * @throws ResourceNotFoundException if the organization, employee, or project is not found.
     */
    @Transactional
    public AttendanceResponseDto markAbsent(Long employeeId, Long projectId, LocalDate date) {
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + orgId + " was not found"));

        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        Project project = projectRepository.findByIdAndOrganization_Id(projectId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project with ID " + projectId + " was not found in this organization"));

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

        return attendanceMapper.toResponseDto(attendanceRepository.save(attendance),fileStorageService);
    }

    /**
     * Marks an employee on leave for a day on a project, creating the record if none exists.
     *
     * <p>Links the attendance to the originating leave via {@code leaveId} and {@code leaveType},
     * and the record is approved.
     *
     * @param employeeId The employee's ID.
     * @param projectId The project's ID.
     * @param date The attendance date.
     * @param leaveId The ID of the approved leave that covers this day.
     * @param leaveType The leave type label to record.
     * @return The updated or created attendance record.
     * @throws ResourceNotFoundException if the organization, employee, or project is not found.
     */
    @Transactional
    public AttendanceResponseDto markLeave(Long employeeId, Long projectId, LocalDate date,
                                            Long leaveId, String leaveType) {
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + orgId + " was not found"));

        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        Project project = projectRepository.findByIdAndOrganization_Id(projectId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project with ID " + projectId + " was not found in this organization"));

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

        return attendanceMapper.toResponseDto(attendanceRepository.save(attendance),fileStorageService);
    }

    /**
     * Builds a monthly attendance summary for an employee.
     *
     * <p>Gathers the month's records and delegates the day counts, worked-hours totals, and
     * attendance percentage to {@link AttendanceCalculationService}.
     *
     * @param employeeId The employee's ID.
     * @param month The month (1-12).
     * @param year The calendar year.
     * @return The monthly summary.
     * @throws ResourceNotFoundException if the employee is not found in this organization.
     */
    @Transactional(readOnly = true)
    public AttendanceSummaryDto getMonthlySummary(Long employeeId, int month, int year) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());

        List<Attendance> records = attendanceRepository
                .findByEmployeeIdAndAttendanceDateBetween(employeeId, from, to);

        return calculationService.buildMonthlySummary(
                employeeId, employee.getEmployeeName(), records, month, year);
    }

    /**
     * Deletes an attendance record and its cascaded clock events and regularizations.
     *
     * @param attendanceId The ID of the attendance record to delete.
     * @throws ResourceNotFoundException if no record with the given ID exists in this organization.
     */
    @Transactional
    public void deleteAttendance(Long attendanceId) {
        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(attendanceId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record with ID " + attendanceId + " was not found"));
        attendanceRepository.delete(attendance);
    }
}
