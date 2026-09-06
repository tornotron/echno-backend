package org.tornotron.echno_backend.attendance;

import org.tornotron.echno_backend.common.payload.PayloadValidator;
import jakarta.validation.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
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
import org.tornotron.echno_backend.attendance.service.AttendanceGeofenceService;
import org.tornotron.echno_backend.attendance.service.AttendanceSettingsService;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.GeofenceExceptionReasonRequiredException;
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
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Records site attendance from clock events and derives each day's worked-hours and status.
 *
 * <p>Check-in opens the day's record and its first clock event; later punches append to it. Each
 * change runs {@link AttendanceCalculationService} to recompute totals and status against the
 * shift. Enforces per-project photo and geolocation requirements from the effective settings, one
 * record per employee/date/project, and clock-event ordering. Each punch is measured against the
 * project's geofence, and a self-marked punch from outside it is recorded with the employee's
 * reason and held for their reporting manager rather than refused. Uploaded photos are cleaned from
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
    private final AttendanceGeofenceService geofenceService;

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
                             AttendanceSecurityService attendanceSecurity,
                             AttendanceGeofenceService geofenceService) {
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
        this.geofenceService = geofenceService;
    }

    /**
     * Records who took the punch, measures it against the project's geofence when that measurement
     * means anything, and holds the day for a decision when a self-marked punch fell outside the
     * site.
     *
     * <p>Only a punch an employee took on their own account is measured. The coordinates on a
     * request are the submitting device's, so on the mark-for-team path they are the supervisor's
     * position, not the employee's. Deriving "this employee was inside the site" from where their
     * supervisor was standing would put a claim about the wrong person into the same column as real
     * measurements, which is the defect this whole evaluation exists to remove. A punch entered for
     * somebody else is therefore left unevaluated, the state the column now has words for, and
     * {@code recordedById} says why.
     *
     * <p>Whether a supervisor marking their team should also have to satisfy the site's location
     * and photo rules is an open product question and is deliberately not answered here: the
     * mark-for-team path keeps exactly the requirements it has today.
     *
     * <p>Being outside the fence never refuses the punch. The employee supplies a reason, the
     * reason is stored on the punch it explains, and the day waits on their reporting manager. A
     * site engineer at head office marks attendance and says why.
     *
     * @param event The clock event being written, stamped in place.
     * @param attendance The day's record the event belongs to.
     * @param employee The employee the day belongs to, used to name the approver.
     * @param project The project being marked against, or null when it cannot be resolved.
     * @param settings The effective attendance settings for that project.
     * @param latitude The latitude on the request.
     * @param longitude The longitude on the request.
     * @param exceptionReason The reason given for marking from outside the fence, if any.
     * @param selfMarked Whether the caller is the employee the day belongs to.
     * @throws GeofenceExceptionReasonRequiredException if a self-marked punch fell outside the
     *     fence and carried no reason.
     */
    private void applyGeofence(ClockEvent event,
                               Attendance attendance,
                               Employee employee,
                               Project project,
                               AttendanceSettings settings,
                               Double latitude,
                               Double longitude,
                               String exceptionReason,
                               boolean selfMarked) {
        Employee recorder = resolveCurrentEmployee();
        event.setRecordedById(recorder == null ? null : recorder.getId());

        AttendanceGeofenceService.Evaluation evaluation = selfMarked
                ? geofenceService.evaluate(project, settings, latitude, longitude)
                : AttendanceGeofenceService.Evaluation.notEvaluated();
        geofenceService.applyTo(event, evaluation);

        if (!evaluation.isOutsideFence()) {
            return;
        }

        if (exceptionReason == null || exceptionReason.isBlank()) {
            throw new GeofenceExceptionReasonRequiredException(
                    evaluation.distanceMeters(), evaluation.radiusMeters());
        }

        event.setGeofenceExceptionReason(exceptionReason.trim());
        attendance.setRequiresGeofenceApproval(true);
        if (attendance.getGeofenceApproverId() == null) {
            attendance.setGeofenceApproverId(geofenceService.resolveApprover(employee, project));
        }
        // A day that had already been decided is decided again, because the exception is new
        // information. The punch keeps its own reason and distance, so what happened is still on
        // the record; what is cleared is the standing decision, which no longer stands.
        attendance.setApprovalStatus(ApprovalStatus.PENDING);
        attendance.setApprovedBy(null);
        attendance.setApprovedById(null);
        attendance.setApprovedAt(null);
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
                .isRegularized(false)
                .remarks(dto.getRemarks())
                .organization(org)
                .build();

        applyGeofence(clockEvent, attendance, employee, project, settings,
                dto.getLatitude(), dto.getLongitude(), dto.getGeofenceExceptionReason(),
                attendanceSecurity.isSelfMarking(dto.getEmployeeId()));

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

        return attendanceMapper.toResponseDto(savedAttendance);
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
                .isRegularized(false)
                .remarks(dto.getRemarks())
                .organization(org)
                .build();

        // Both are looked up only to evaluate the geofence, and both are optional to it: the
        // project supplies the centre and the employee names the approver, and a missing one
        // leaves the punch unevaluated rather than failing a punch that is otherwise valid.
        Project project = projectRepository
                .findByIdAndOrganization_Id(attendance.getProjectId(), orgId)
                .orElse(null);
        Employee employee = employeeRepository
                .findByIdAndOrganizationId(attendance.getEmployeeId(), orgId)
                .orElse(null);

        applyGeofence(clockEvent, attendance, employee, project, settings,
                dto.getLatitude(), dto.getLongitude(), dto.getGeofenceExceptionReason(),
                attendanceSecurity.isSelfMarking(attendance.getEmployeeId()));

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

        return attendanceMapper.toResponseDto(savedAttendance);
    }

    /**
     * Retrieves a single attendance record by its ID.
     *
     * <p>Who may read it is settled here against the stored record rather than in the
     * {@code @PreAuthorize} guard, the same way {@link #requireActorMayApprove} settles who may
     * decide one. The id on the request names a record, so it identifies no person for an
     * annotation to check; the employee and the designated approver are columns the record
     * carries. The response is the same {@link AttendanceResponseDto} the employee-scoped listing
     * beside it returns, down to the check-in coordinates and the attachment, so the two reads
     * answer to the same policy.
     *
     * @param id The ID of the attendance record.
     * @return The attendance record.
     * @throws ResourceNotFoundException if no record with the given ID exists in this organization.
     * @throws AccessDeniedException if the caller may not read this employee's records.
     */
    @Transactional(readOnly = true)
    public AttendanceResponseDto getAttendanceById(Long id) {
        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record with ID " + id + " was not found"));
        requireActorMayViewRecord(attendance);
        return attendanceMapper.toResponseDto(attendance);
    }

    /**
     * Refuses the call unless the caller may read this record.
     *
     * <p>The policy is {@link AttendanceSecurityService#canViewAttendanceRecord}: the employee
     * themselves, a holder of an attendance record-management role, or the approver the record
     * names. The last of those is why this cannot be an annotation, and it has to be here rather
     * than left out, because a manager asked to decide a geofence exception has to be able to look
     * at the record they are deciding.
     *
     * @param attendance The record being read, loaded from the database.
     * @throws AccessDeniedException if the caller may not read it.
     */
    private void requireActorMayViewRecord(Attendance attendance) {
        if (attendanceSecurity.canViewAttendanceRecord(
                attendance.getEmployeeId(), attendance.getGeofenceApproverId())) {
            return;
        }
        throw new AccessDeniedException(
                "Attendance records can only be read by the employee they belong to, by a holder "
                        + "of an attendance record-management role, or by the approver the record "
                        + "names");
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
                .map(attendance -> attendanceMapper.toResponseDto(attendance))
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
                : "%" + search.toLowerCase(Locale.ROOT) + "%";
        return attendanceRepository
                .findWithFilters(projectId, date, status, searchPattern, pageable)
                .map(attendance -> attendanceMapper.toResponseDto(attendance)).getContent();
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
     * @throws AccessDeniedException if the caller is neither a record manager nor the approver the
     *     record names, or is the employee whose geofence exception is being decided.
     */
    @Transactional
    public AttendanceResponseDto approveAttendance(Long attendanceId, AttendanceApprovalDto dto) {
        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(attendanceId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record with ID " + attendanceId + " was not found"));

        requireActorMayApprove(attendance);

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

        return attendanceMapper.toResponseDto(attendanceRepository.save(attendance));
    }

    /**
     * One page of the attendance days waiting on the signed-in caller's decision.
     *
     * <p>The routing built in #681 named an approver and had nowhere to show them what they had
     * been named on. The only two listings are a project on one required date and one employee
     * over a range, so an approver with people on several sites had to guess a site and a day, and
     * a day held three days ago was invisible to anyone not already looking for it. A decision
     * routed to a person who cannot find it does not get made.
     *
     * <p>The caller comes from the session and never from a parameter. The leave queue took an
     * {@code approverId} under a guard that only asked for a role, so an administrator read any
     * colleague's queue while the line managers a chain is built from could read none of their
     * own; #683 took the parameter off, and this pair is built that way from the start.
     *
     * <p>What is in it, and why it is the geofence exceptions rather than everything pending, is
     * argued in {@link AttendanceApprovalQueueSpecifications}. In short: every record is born
     * {@code PENDING} and stays there until somebody decides it, so pending is an ordinary
     * record's resting state and a queue of all of them would be the whole table.
     *
     * @param pageNo Zero-based page number.
     * @param pageSize Rows per page.
     * @return The page of records, newest day first.
     * @throws AccessDeniedException if the caller has no employee record in this organization, so
     *     there is nobody for a queue to belong to.
     */
    @Transactional(readOnly = true)
    public Page<AttendanceResponseDto> getPendingApprovals(int pageNo, int pageSize) {
        return attendanceRepository
                .findAll(queueWaitingOnCaller("read your attendance approval queue"),
                        PageRequest.of(pageNo, pageSize, AttendanceApprovalQueueSpecifications.QUEUE_ORDER))
                .map(attendanceMapper::toResponseDto);
    }

    /**
     * How many attendance days are waiting on the caller, for the badge a client draws on the menu.
     *
     * <p>A real count query over the same predicate the listing uses, rather than the size of a
     * page of it. A page total is not a count once the queue is longer than a page, and Spring
     * skips the count query altogether when the first page comes back short, so a badge built from
     * a page would be right only while it did not matter.
     *
     * @return The number of records waiting on the caller.
     * @throws AccessDeniedException if the caller has no employee record in this organization.
     */
    @Transactional(readOnly = true)
    public long getPendingApprovalCount() {
        return attendanceRepository.count(queueWaitingOnCaller("count your attendance approval queue"));
    }

    /**
     * The queue predicate for whoever is signed in, refusing a caller there is no queue for.
     *
     * <p>Both halves of the queue read the caller the same way, so the badge cannot be counted for
     * one person and the list served for another.
     *
     * @param action What the caller was trying to do, named in the refusal.
     * @return The specification matching the days waiting on the caller.
     * @throws AccessDeniedException if the caller has no employee record in this organization.
     */
    private Specification<Attendance> queueWaitingOnCaller(String action) {
        Employee caller = resolveCurrentEmployee();
        if (caller == null) {
            throw new AccessDeniedException(
                    "You have no employee record in this organization, so there is no attendance "
                            + "approval queue that is yours. Ask an administrator to add you to the "
                            + "organization as an employee before you " + action + ".");
        }
        return AttendanceApprovalQueueSpecifications.waitingOn(
                caller.getId(), attendanceSecurity.canManageRecords());
    }

    /**
     * Refuses the call unless the caller may decide this record's approval.
     *
     * <p>The record-management roles decide every attendance record, as they did before, and the
     * approver a geofence exception names is added to them. That addition is the reason the check
     * lives here rather than in the {@code @PreAuthorize} guard, for the same reason
     * {@link #requireActorMayRecordFor} does: the approver is a column on the stored record, which
     * the annotation cannot see. Reading an approver id off the request instead would let any
     * caller nominate themselves.
     *
     * <p>An employee never decides their own geofence exception, whatever roles they hold. The
     * decision exists to have someone else vouch for the absence, and a self-approval is not that.
     * This applies only to records flagged for a geofence decision; who may approve an ordinary
     * record is unchanged.
     *
     * @param attendance The record being decided, read from the database.
     * @throws AccessDeniedException if the caller may not decide it.
     */
    private void requireActorMayApprove(Attendance attendance) {
        if (Boolean.TRUE.equals(attendance.getRequiresGeofenceApproval())
                && attendanceSecurity.isSelfMarking(attendance.getEmployeeId())) {
            throw new AccessDeniedException(
                    "A geofence exception has to be approved by someone other than the employee it "
                            + "belongs to");
        }
        if (attendanceSecurity.canDecideApproval(attendance.getGeofenceApproverId())) {
            return;
        }
        throw new AccessDeniedException(
                "Attendance can only be approved by an attendance record manager, or by the "
                        + "approver the record names");
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

        return attendanceMapper.toResponseDto(attendanceRepository.save(attendance));
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

        return attendanceMapper.toResponseDto(attendanceRepository.save(attendance));
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
