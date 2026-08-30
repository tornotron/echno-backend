package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.ValidationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.attendance.*;
import org.tornotron.echno_backend.attendance.dto.MovementRecordCreationDto;
import org.tornotron.echno_backend.attendance.dto.MovementRecordDto;
import org.tornotron.echno_backend.attendance.mapper.MovementRecordMapper;
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

@Service
public class MovementRecordService {

    private final MovementRecordRepository movementRecordRepository;
    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final OrganizationRepository organizationRepository;
    private final AttendanceSettingsService settingsService;
    private final MovementRecordMapper movementRecordMapper;
    private final AttendanceSecurityService attendanceSecurity;

    public MovementRecordService(MovementRecordRepository movementRecordRepository,
                                  AttendanceRepository attendanceRepository,
                                  EmployeeRepository employeeRepository,
                                  OrganizationRepository organizationRepository,
                                  AttendanceSettingsService settingsService,
                                  MovementRecordMapper movementRecordMapper,
                                  AttendanceSecurityService attendanceSecurity) {
        this.movementRecordRepository = movementRecordRepository;
        this.attendanceRepository = attendanceRepository;
        this.employeeRepository = employeeRepository;
        this.organizationRepository = organizationRepository;
        this.settingsService = settingsService;
        this.movementRecordMapper = movementRecordMapper;
        this.attendanceSecurity = attendanceSecurity;
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

    @Transactional
    public MovementRecordDto verifyMovement(Long movementId, String verifiedBy) {
        MovementRecord record = movementRecordRepository.findByIdAndOrganization_Id(movementId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Movement record with ID " + movementId + " was not found"));

        record.setIsVerified(true);
        record.setVerifiedBy(verifiedBy);
        record.setVerifiedAt(LocalDateTime.now());

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
