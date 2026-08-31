package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.attendance.*;
import org.tornotron.echno_backend.attendance.dto.MovementRecordCreationDto;
import org.tornotron.echno_backend.attendance.dto.MovementRecordDto;
import org.tornotron.echno_backend.attendance.mapper.MovementRecordMapper;
import org.tornotron.echno_backend.attendance.service.AttendanceActorResolver.Actor;
import org.tornotron.echno_backend.common.approval.ApprovalParty;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MovementRecordService {

    private final MovementRecordRepository movementRecordRepository;
    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final OrganizationRepository organizationRepository;
    private final AttendanceSettingsService settingsService;
    private final MovementRecordMapper movementRecordMapper;
    private final AttendanceSecurityService attendanceSecurity;
    private final AttendanceActorResolver actorResolver;
    private final SelfApprovalPolicy selfApprovalPolicy;

    public MovementRecordService(MovementRecordRepository movementRecordRepository,
                                  AttendanceRepository attendanceRepository,
                                  EmployeeRepository employeeRepository,
                                  OrganizationRepository organizationRepository,
                                  AttendanceSettingsService settingsService,
                                  MovementRecordMapper movementRecordMapper,
                                  AttendanceSecurityService attendanceSecurity,
                                  AttendanceActorResolver actorResolver,
                                  SelfApprovalPolicy selfApprovalPolicy) {
        this.movementRecordRepository = movementRecordRepository;
        this.attendanceRepository = attendanceRepository;
        this.employeeRepository = employeeRepository;
        this.organizationRepository = organizationRepository;
        this.settingsService = settingsService;
        this.movementRecordMapper = movementRecordMapper;
        this.attendanceSecurity = attendanceSecurity;
        this.actorResolver = actorResolver;
        this.selfApprovalPolicy = selfApprovalPolicy;
    }

    /**
     * Refuses the call unless the caller is the given employee or holds an attendance
     * record-management role. See {@link AttendanceSecurityService#canRecordFor}.
     */
    private void requireActorMayRecordFor(Long employeeId) {
        if (attendanceSecurity.canRecordFor(employeeId)) {
            return;
        }
        throw new AccessDeniedException(
                "Movements can only be recorded for yourself, unless you hold an attendance "
                        + "record-management role");
    }

    /**
     * Logs a movement on an attendance record.
     *
     * <p>The employee named on the call and the employee the attendance record belongs to must
     * each be the caller or fall under the caller's record-management role. Both ids arrive from
     * the request, so checking them here against the caller is what stops one member of the
     * tenant writing movements onto a colleague's attendance trail; the {@code @PreAuthorize}
     * guard on the handlers only sees the caller's word.
     *
     * @throws AccessDeniedException if the caller is neither the employee involved nor a holder
     *     of an attendance record-management role.
     */
    @Transactional
    public MovementRecordDto addMovement(MovementRecordCreationDto dto, Long employeeId) {
        requireActorMayRecordFor(employeeId);
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + orgId + " was not found"));

        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(dto.getAttendanceId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Attendance record with ID " + dto.getAttendanceId() + " was not found"));

        if (!attendance.getEmployeeId().equals(employeeId)) {
            requireActorMayRecordFor(attendance.getEmployeeId());
        }

        AttendanceSettings settings = settingsService.resolveEffectiveSettings(orgId, attendance.getProjectId());

        if (!settings.getMovementTrackingEnabled()) {
            throw new ValidationException("Movement tracking is not enabled for this project");
        }

        if (settings.getMovementGeolocationRequired()
                && (dto.getStartLatitude() == null || dto.getStartLongitude() == null)) {
            throw new ValidationException("Latitude and longitude are required for movement records in this project");
        }

        MovementRecord record = MovementRecord.builder()
                .attendance(attendance)
                .employeeId(employee.getId())
                .employeeName(employee.getEmployeeName())
                .movementType(dto.getMovementType())
                .fromLocation(dto.getFromLocation())
                .toLocation(dto.getToLocation())
                .startTime(dto.getStartTime())
                .endTime(dto.getEndTime())
                .purpose(dto.getPurpose())
                .remarks(dto.getRemarks())
                .startLatitude(dto.getStartLatitude())
                .startLongitude(dto.getStartLongitude())
                .endLatitude(dto.getEndLatitude())
                .endLongitude(dto.getEndLongitude())
                .distanceKm(dto.getDistanceKm())
                .attachments(movementRecordMapper.serializeAttachments(dto.getAttachments()))
                .isVerified(false)
                .organization(org)
                .build();

        if (dto.getEndTime() != null) {
            long minutes = Duration.between(dto.getStartTime(), dto.getEndTime()).toMinutes();
            record.setDurationMinutes((int) minutes);
        }

        return movementRecordMapper.toDto(movementRecordRepository.save(record));
    }

    /**
     * Records that a movement has been checked, stamping the checker from the session.
     *
     * <p>The verifier used to arrive as a request parameter, and the web client sent whatever
     * name it had to hand, so the person a movement record named as its verifier was a value the
     * caller chose. A verification is a statement that somebody looked at the reported distance,
     * times and purpose; a name typed by the person asking for the stamp is not evidence that
     * anyone did. It is read from the authenticated session here instead, which is the shape
     * {@code ConstructionPaymentService.verify} and
     * {@link AttendanceRegularizationService#processRegularization} both settled on.
     *
     * <p>The employee the movement belongs to cannot verify it, on the rule every other
     * second-pair-of-eyes check here follows: see {@link SelfApprovalPolicy}. A movement record is
     * the employee's own account of where they went during a work day, so the check on it has to
     * come from somebody else. The record already carries the employee id to compare against, so
     * unlike the payment voucher this needed no new column. A verifier who holds a
     * record-management role without having an employee record in the tenant shares no identity
     * with the employee, and the policy allows that verification through at WARN rather than
     * stranding it.
     *
     * <p>A movement already verified is not verified again. Re-verifying is not an idempotent
     * no-op, it would overwrite a stamp somebody else's check produced, so the existing stamp
     * stands. There is deliberately no action to clear one. The row is read under a write lock so
     * that two verifications arriving at once cannot each read it as unverified and both stamp,
     * which would replace a verification through the very guard meant to prevent it.
     *
     * @param movementId The movement record to verify.
     * @return The verified movement, naming the verifier.
     * @throws ResourceNotFoundException if no such movement exists in this organization.
     * @throws InvalidRequestException if the movement is already verified, if the verifier is the
     *         employee the movement belongs to and does not hold the break-glass role, or if the
     *         session resolves to no user of this organization.
     */
    @Transactional
    public MovementRecordDto verifyMovement(Long movementId) {
        MovementRecord record = movementRecordRepository
                .lockByIdAndOrganizationId(movementId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Movement record with ID " + movementId + " was not found"));

        if (Boolean.TRUE.equals(record.getIsVerified())) {
            throw new InvalidRequestException(
                    "Movement record with ID " + movementId + " has already been verified, and a "
                            + "verification cannot be replaced");
        }

        Actor verifier = actorResolver.resolveCurrentActor();
        selfApprovalPolicy.checkSelfApproval(
                new ApprovalParty(null, record.getEmployeeId()),
                verifier.party(),
                "Movement record with ID " + movementId);

        record.setIsVerified(true);
        record.setVerifiedBy(verifier.name());
        record.setVerifiedById(verifier.employeeId());
        record.setVerifiedAt(LocalDateTime.now());

        log.info("Verified movement record {}", movementId);
        return movementRecordMapper.toDto(movementRecordRepository.save(record));
    }

    @Transactional(readOnly = true)
    public List<MovementRecordDto> getMovementsByAttendance(Long attendanceId) {
        return movementRecordRepository.findByAttendanceIdOrderByStartTimeAsc(attendanceId)
                .stream()
                .map(movementRecordMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public MovementRecordDto getMovementById(Long id) {
        MovementRecord record = movementRecordRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Movement record with ID " + id + " was not found"));
        return movementRecordMapper.toDto(record);
    }
}
