package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.attendance.*;
import org.tornotron.echno_backend.attendance.dto.*;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;
import org.tornotron.echno_backend.attendance.mapper.AttendanceRegularizationMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AttendanceRegularizationService {

    private final AttendanceRegularizationRepository regularizationRepository;
    private final AttendanceRepository attendanceRepository;
    private final OrganizationRepository organizationRepository;
    private final AttendanceSettingsService settingsService;
    private final AttendanceCalculationService calculationService;
    private final AttendanceRegularizationMapper regularizationMapper;

    public AttendanceRegularizationService(AttendanceRegularizationRepository regularizationRepository,
                                            AttendanceRepository attendanceRepository,
                                            OrganizationRepository organizationRepository,
                                            AttendanceSettingsService settingsService,
                                            AttendanceCalculationService calculationService,
                                            AttendanceRegularizationMapper regularizationMapper) {
        this.regularizationRepository = regularizationRepository;
        this.attendanceRepository = attendanceRepository;
        this.organizationRepository = organizationRepository;
        this.settingsService = settingsService;
        this.calculationService = calculationService;
        this.regularizationMapper = regularizationMapper;
    }

    @Transactional
    public AttendanceRegularizationDto submitRequest(RegularizationRequestDto dto, String requestedBy) {
        return submitRequest(dto, requestedBy, null);
    }

    @Transactional
    public AttendanceRegularizationDto submitRequest(RegularizationRequestDto dto, String requestedBy,
                                                     Long requestedById) {
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + orgId + " was not found"));

        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(dto.getAttendanceId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Attendance record with ID " + dto.getAttendanceId() + " was not found"));

        AttendanceSettings settings = settingsService.resolveEffectiveSettings(orgId, attendance.getProjectId());

        if (!settings.getAllowSelfRegularization()) {
            throw new ValidationException(
                    "Self-service regularization is not enabled for this project. Please contact your manager.");
        }

        // Check monthly limit
        LocalDate attendanceDate = attendance.getAttendanceDate();
        LocalDateTime startOfMonth = attendanceDate.withDayOfMonth(1).atStartOfDay();
        LocalDateTime startOfNextMonth = startOfMonth.plusMonths(1);

        long usedThisMonth = regularizationRepository
                .countApprovedRegularizationsInMonth(requestedBy, startOfMonth, startOfNextMonth);

        if (usedThisMonth >= settings.getMaxRegularizationDaysPerMonth()) {
            throw new ValidationException(
                    "The monthly regularization limit of " + settings.getMaxRegularizationDaysPerMonth()
                            + " requests has been reached");
        }

        // Check for existing pending request
        regularizationRepository.findByAttendanceId(dto.getAttendanceId())
                .ifPresent(existing -> {
                    if (existing.getStatus() == RegularizationStatus.PENDING) {
                        throw new ValidationException(
                                "A regularization request is already pending for attendance record "
                                        + dto.getAttendanceId());
                    }
                });

        AttendanceRegularization regularization = AttendanceRegularization.builder()
                .attendance(attendance)
                .reason(dto.getReason())
                .requestedBy(requestedBy)
                .requestedById(requestedById)
                .status(settings.getRegularizationApprovalRequired()
                        ? RegularizationStatus.PENDING : RegularizationStatus.APPROVED)
                .missingEvents(regularizationMapper.serializeMissingEvents(dto.getMissingEvents()))
                // Kept whichever path the request takes. When approval is required the events sit
                // here until the manager decides; without them an approval had nothing to apply.
                .requestedEvents(regularizationMapper.serializeRequestedEvents(dto.getCorrectedEvents()))
                .organization(org)
                .build();

        // Auto-approving projects take effect immediately, so the corrections are written now.
        if (!settings.getRegularizationApprovalRequired()) {
            applyCorrectedEvents(attendance, dto.getCorrectedEvents(), org);
            if (attendance.getShiftTiming() != null) {
                calculationService.recalculate(attendance, attendance.getShiftTiming());
            }
            attendanceRepository.save(attendance);
        }

        return regularizationMapper.toDto(regularizationRepository.save(regularization));
    }

    @Transactional
    public AttendanceRegularizationDto processRegularization(Long regularizationId,
                                                              RegularizationActionDto dto,
                                                              String approvedBy) {
        return processRegularization(regularizationId, dto, approvedBy, null);
    }

    @Transactional
    public AttendanceRegularizationDto processRegularization(Long regularizationId,
                                                              RegularizationActionDto dto,
                                                              String approvedBy,
                                                              Long approvedById) {
        AttendanceRegularization regularization = regularizationRepository.findByIdAndOrganization_Id(regularizationId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Regularization request with ID " + regularizationId + " was not found"));

        if (regularization.getStatus() != RegularizationStatus.PENDING) {
            throw new ValidationException(
                    "Regularization request " + regularizationId + " has already been "
                            + regularization.getStatus().toString().toLowerCase()
                            + " and can no longer be actioned");
        }

        regularization.setStatus(dto.getStatus());
        regularization.setApprovedBy(approvedBy);
        regularization.setApprovedById(approvedById);
        regularization.setApprovedAt(LocalDateTime.now());
        regularization.setRejectionReason(dto.getRejectionReason());

        if (dto.getStatus() == RegularizationStatus.APPROVED) {
            Attendance attendance = regularization.getAttendance();
            // Write the times the employee asked for before recomputing. Approval used to recompute
            // over the events that already existed, which for a day with no clock-in at all meant
            // recomputing over nothing and leaving the record exactly as broken as it was.
            applyCorrectedEvents(
                    attendance,
                    regularizationMapper.deserializeRequestedEvents(regularization.getRequestedEvents()),
                    regularization.getOrganization());
            if (attendance.getShiftTiming() != null) {
                calculationService.recalculate(attendance, attendance.getShiftTiming());
            }
            attendanceRepository.save(attendance);
        }

        return regularizationMapper.toDto(regularizationRepository.save(regularization));
    }

    @Transactional(readOnly = true)
    public List<AttendanceRegularizationDto> getPendingRegularizations() {
        return regularizationRepository.findByStatus(RegularizationStatus.PENDING)
                .stream()
                .map(regularizationMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AttendanceRegularizationDto getRegularizationById(Long id) {
        AttendanceRegularization regularization = regularizationRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Regularization request with ID " + id + " was not found"));
        return regularizationMapper.toDto(regularization);
    }

    /**
     * Writes the employee's corrected clock events onto the attendance record.
     *
     * <p>An event type already present is left alone. A regularization exists to supply what is
     * missing, not to overwrite a real clock event, and skipping duplicates keeps the operation safe
     * to reach twice, for instance when a rejected request is resubmitted and then approved.
     *
     * @param attendance      the record being corrected
     * @param correctedEvents the events to add; {@code null} or empty is a no-op
     * @param org             the owning organization, stamped onto each new event
     */
    private void applyCorrectedEvents(Attendance attendance,
                                       List<ClockEventCreationDto> correctedEvents,
                                       Organization org) {
        if (correctedEvents == null || correctedEvents.isEmpty()) {
            return;
        }
        if (attendance.getClockEvents() == null) {
            attendance.setClockEvents(new ArrayList<>());
        }
        Set<ClockEventType> existing = attendance.getClockEvents().stream()
                .map(ClockEvent::getEventType)
                .collect(Collectors.toSet());

        for (ClockEventCreationDto req : correctedEvents) {
            if (req.getEventType() == null || !existing.add(req.getEventType())) {
                continue;
            }
            ClockEvent event = ClockEvent.builder()
                    .attendance(attendance)
                    .eventType(req.getEventType())
                    .eventTimestamp(req.getEventTimestamp())
                    .latitude(req.getLatitude())
                    .longitude(req.getLongitude())
                    .projectId(attendance.getProjectId())
                    .projectName(attendance.getProjectName())
                    .isRegularized(true)
                    .regularizationReason("Self-regularized")
                    .isWithinGeofence(false)
                    .distanceFromProject(0.0)
                    .organization(org)
                    .build();
            attendance.getClockEvents().add(event);
        }
    }
}
