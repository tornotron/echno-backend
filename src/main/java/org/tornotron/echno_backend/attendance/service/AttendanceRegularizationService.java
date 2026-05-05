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
import java.util.List;
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
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(dto.getAttendanceId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance not found"));

        AttendanceSettings settings = settingsService.resolveEffectiveSettings(orgId, attendance.getProjectId());

        if (!settings.getAllowSelfRegularization()) {
            throw new ValidationException(
                    "Self regularization is not allowed for this project. Contact your manager.");
        }

        // Check monthly limit
        LocalDate attendanceDate = attendance.getAttendanceDate();
        LocalDateTime startOfMonth = attendanceDate.withDayOfMonth(1).atStartOfDay();
        LocalDateTime startOfNextMonth = startOfMonth.plusMonths(1);

        long usedThisMonth = regularizationRepository
                .countApprovedRegularizationsInMonth(requestedBy, startOfMonth, startOfNextMonth);

        if (usedThisMonth >= settings.getMaxRegularizationDaysPerMonth()) {
            throw new ValidationException(
                    "Monthly regularization limit of " + settings.getMaxRegularizationDaysPerMonth() + " has been reached");
        }

        // Check for existing pending request
        regularizationRepository.findByAttendanceId(dto.getAttendanceId())
                .ifPresent(existing -> {
                    if (existing.getStatus() == RegularizationStatus.PENDING) {
                        throw new ValidationException(
                                "A regularization request is already pending for this attendance");
                    }
                });

        AttendanceRegularization regularization = AttendanceRegularization.builder()
                .attendance(attendance)
                .reason(dto.getReason())
                .requestedBy(requestedBy)
                .status(settings.getRegularizationApprovalRequired()
                        ? RegularizationStatus.PENDING : RegularizationStatus.APPROVED)
                .missingEvents(regularizationMapper.serializeMissingEvents(dto.getMissingEvents()))
                .organization(org)
                .build();

        // If auto-approved, apply corrected events
        if (!settings.getRegularizationApprovalRequired() && dto.getCorrectedEvents() != null) {
            applyCorrectedEvents(attendance, dto.getCorrectedEvents(), org);
        }

        return regularizationMapper.toDto(regularizationRepository.save(regularization));
    }

    @Transactional
    public AttendanceRegularizationDto processRegularization(Long regularizationId,
                                                              RegularizationActionDto dto,
                                                              String approvedBy) {
        AttendanceRegularization regularization = regularizationRepository.findByIdAndOrganization_Id(regularizationId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Regularization not found"));

        if (regularization.getStatus() != RegularizationStatus.PENDING) {
            throw new ValidationException("Regularization is not in pending state");
        }

        regularization.setStatus(dto.getStatus());
        regularization.setApprovedBy(approvedBy);
        regularization.setApprovedAt(LocalDateTime.now());
        regularization.setRejectionReason(dto.getRejectionReason());

        if (dto.getStatus() == RegularizationStatus.APPROVED) {
            Attendance attendance = regularization.getAttendance();
            if (attendance.getShiftTiming() != null) {
                calculationService.recalculate(attendance, attendance.getShiftTiming());
                attendanceRepository.save(attendance);
            }
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
                .orElseThrow(() -> new ResourceNotFoundException("Regularization not found"));
        return regularizationMapper.toDto(regularization);
    }

    private void applyCorrectedEvents(Attendance attendance,
                                       List<ClockEventCreationDto> correctedEvents,
                                       Organization org) {
        for (ClockEventCreationDto req : correctedEvents) {
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
