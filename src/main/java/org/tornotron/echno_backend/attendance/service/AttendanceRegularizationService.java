package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.attendance.*;
import org.tornotron.echno_backend.attendance.dto.*;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;
import org.tornotron.echno_backend.attendance.mapper.AttendanceRegularizationMapper;
import org.tornotron.echno_backend.attendance.service.AttendanceActorResolver.Actor;
import org.tornotron.echno_backend.common.approval.ApprovalParty;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Raising and deciding regularization requests.
 *
 * <p>Both the person who raises a request and the person who decides it are read from the
 * authenticated session, never from the request. They used to arrive as query parameters, which
 * meant a caller chose the name and id recorded against a corrected attendance record and the
 * accountability on the correction was whatever they typed.
 *
 * <p>Because the two are now trustworthy they can be compared, so an approval goes through
 * {@link SelfApprovalPolicy}: whoever raised a request is not the person who approves it, with the
 * one recorded break-glass exception that policy defines.
 */
@Service
public class AttendanceRegularizationService {

    /** Note carried by the corrected clock events of a request approved under the break-glass role. */
    private static final String SELF_APPROVAL_NOTE =
            " (self-approved: raised and approved by the same person)";

    private final AttendanceRegularizationRepository regularizationRepository;
    private final AttendanceRepository attendanceRepository;
    private final OrganizationRepository organizationRepository;
    private final AttendanceSettingsService settingsService;
    private final AttendanceCalculationService calculationService;
    private final AttendanceRegularizationMapper regularizationMapper;
    private final AttendanceActorResolver actorResolver;
    private final SelfApprovalPolicy selfApprovalPolicy;
    private final AttendanceSecurityService attendanceSecurity;

    public AttendanceRegularizationService(AttendanceRegularizationRepository regularizationRepository,
                                            AttendanceRepository attendanceRepository,
                                            OrganizationRepository organizationRepository,
                                            AttendanceSettingsService settingsService,
                                            AttendanceCalculationService calculationService,
                                            AttendanceRegularizationMapper regularizationMapper,
                                            AttendanceActorResolver actorResolver,
                                            SelfApprovalPolicy selfApprovalPolicy,
                                            AttendanceSecurityService attendanceSecurity) {
        this.regularizationRepository = regularizationRepository;
        this.attendanceRepository = attendanceRepository;
        this.organizationRepository = organizationRepository;
        this.settingsService = settingsService;
        this.calculationService = calculationService;
        this.regularizationMapper = regularizationMapper;
        this.actorResolver = actorResolver;
        this.selfApprovalPolicy = selfApprovalPolicy;
        this.attendanceSecurity = attendanceSecurity;
    }

    /**
     * Files a regularization request against an attendance record.
     *
     * <p>The requester is taken from the session. It is what the approval is later checked
     * against, so a caller naming someone else as the requester would clear their own way to
     * approve it, and it is also what the monthly cap is counted by.
     *
     * @param dto The attendance record, the reason, the missing events and any corrections.
     * @return The stored request as a DTO.
     * @throws ResourceNotFoundException if the organization or the attendance record is not found.
     * @throws ValidationException if self-service regularization is off for the project, the
     *         monthly cap is reached, or a request is already pending for that record.
     * @throws AccessDeniedException if the caller is neither the employee the attendance record
     *         belongs to nor a holder of an attendance record-management role.
     */
    @Transactional
    public AttendanceRegularizationDto submitRequest(RegularizationRequestDto dto) {
        Actor requester = actorResolver.resolveCurrentActor();
        String requestedBy = requester.name();
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + orgId + " was not found"));

        Attendance attendance = attendanceRepository.findByIdAndOrganization_Id(dto.getAttendanceId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Attendance record with ID " + dto.getAttendanceId() + " was not found"));

        // The attendance record is chosen by the caller, so who it belongs to is settled here
        // against the stored record: its own employee, or someone holding a record-management
        // role. On a project configured to auto-approve, the corrections below are applied
        // immediately, so without this check any member of the tenant could rewrite a
        // colleague's clock events there.
        if (!attendanceSecurity.canRecordFor(attendance.getEmployeeId())) {
            throw new AccessDeniedException(
                    "A regularization can only be raised on your own attendance, unless you hold "
                            + "an attendance record-management role");
        }

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
                .requestedById(requester.employeeId())
                .requestedByUserId(requester.userId())
                .status(settings.getRegularizationApprovalRequired()
                        ? RegularizationStatus.PENDING : RegularizationStatus.APPROVED)
                .missingEvents(regularizationMapper.serializeMissingEvents(dto.getMissingEvents()))
                // Kept whichever path the request takes. When approval is required the events sit
                // here until the manager decides; without them an approval had nothing to apply.
                .requestedEvents(regularizationMapper.serializeRequestedEvents(dto.getCorrectedEvents()))
                .organization(org)
                .build();

        // Auto-approving projects take effect immediately, so the corrections are written now.
        // This path is deliberately outside the self-approval rule: a project configured not to
        // require an approval is the tenant's own standing decision that these corrections do not
        // need a second person, which is exactly the case the setting exists for.
        if (!settings.getRegularizationApprovalRequired()) {
            applyCorrectedEvents(attendance, dto.getCorrectedEvents(), org, false);
            if (attendance.getShiftTiming() != null) {
                calculationService.recalculate(attendance, attendance.getShiftTiming());
            }
            attendanceRepository.save(attendance);
        }

        return regularizationMapper.toDto(regularizationRepository.save(regularization));
    }

    /**
     * Approves or rejects a pending regularization request.
     *
     * <p>The approver is taken from the session, so the record says who actually decided it. An
     * approval then goes through {@link SelfApprovalPolicy}: an approval is the second pair of eyes
     * on a change to an attendance record, so it has to come from someone other than whoever raised
     * the request, unless the approver holds the break-glass role, in which case the corrected clock
     * events carry a note saying the correction was self-approved.
     *
     * <p>A rejection is left outside the rule on purpose. It writes nothing to the attendance
     * record, and refusing a self-rejection would leave an employee unable to withdraw a request
     * they raised by mistake.
     *
     * <p>The rule is applied to whichever identity the two sides share. A request records both the
     * raiser's employee id and their platform user id, because a caller can hold a decision role in
     * the tenant while having no employee record in it yet, and comparing employee ids alone left
     * that caller free to raise a request and approve it themselves. Only a request that shares no
     * identity with the approver at all, which now means one stored before the user id was kept,
     * goes through without the comparison, and the policy logs that it could not be made.
     *
     * @param regularizationId The request to decide.
     * @param dto The decision and, on a rejection, the reason.
     * @return The decided request as a DTO.
     * @throws ResourceNotFoundException if no such request exists in this organization.
     * @throws ValidationException if the request is no longer pending.
     * @throws org.tornotron.echno_backend.common.exception.InvalidRequestException if the approver
     *         raised the request and does not hold the break-glass role.
     */
    @Transactional
    public AttendanceRegularizationDto processRegularization(Long regularizationId,
                                                              RegularizationActionDto dto) {
        AttendanceRegularization regularization = regularizationRepository.findByIdAndOrganization_Id(regularizationId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Regularization request with ID " + regularizationId + " was not found"));

        if (regularization.getStatus() != RegularizationStatus.PENDING) {
            throw new ValidationException(
                    "Regularization request " + regularizationId + " has already been "
                            + regularization.getStatus().toString().toLowerCase()
                            + " and can no longer be actioned");
        }

        Actor approver = actorResolver.resolveCurrentActor();
        boolean selfApproved = dto.getStatus() == RegularizationStatus.APPROVED
                && selfApprovalPolicy.checkSelfApproval(
                        new ApprovalParty(regularization.getRequestedByUserId(),
                                regularization.getRequestedById()),
                        approver.party(),
                        "Regularization request with ID " + regularizationId);

        regularization.setStatus(dto.getStatus());
        regularization.setApprovedBy(approver.name());
        regularization.setApprovedById(approver.employeeId());
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
                    regularization.getOrganization(),
                    selfApproved);
            if (attendance.getShiftTiming() != null) {
                calculationService.recalculate(attendance, attendance.getShiftTiming());
            }
            attendanceRepository.save(attendance);
        }

        return regularizationMapper.toDto(regularizationRepository.save(regularization));
    }

    /**
     * One page of the requests still awaiting a decision.
     *
     * <p>Used to read every pending request the tenant held. That grows with attendance history
     * and nothing bounded it, so the caller now names a page and the web endpoints ask for a
     * single capped one. The page carries the true total, which is what lets a truncated answer
     * say so instead of passing for a complete one.
     *
     * @param pageNo   Zero-based page index.
     * @param pageSize Rows per page.
     * @return That page of pending requests.
     */
    @Transactional(readOnly = true)
    public Page<AttendanceRegularizationDto> getPendingRegularizations(int pageNo, int pageSize) {
        return regularizationRepository
                .findByStatus(RegularizationStatus.PENDING, PageRequest.of(pageNo, pageSize, AttendanceRegularizationSpecifications.LIST_ORDER))
                .map(regularizationMapper::toDto);
    }

    /**
     * One page of the register, narrowed by any of status, approver and requester.
     *
     * <p>The register's only listing was pending-only, which made the approver impossible to
     * filter on: {@link #processRegularization} stamps the approver in the same call that moves
     * the request off {@code PENDING}, so a row with an approver was never in the list and a row
     * in the list never had one. Widening the listing rather than adding a second one keeps the
     * register a single collection; a "decided" endpoint beside a "pending" one would have to be
     * stitched together by the client, which is the partial-view failure the pending-only listing
     * already caused.
     *
     * <p>Every filter is applied in the query, not to the returned page. Narrowing a page in the
     * browser hides the matches that fall outside it while still reading as a complete answer,
     * which is the same defect one step further along.
     *
     * <p>The approver and the rejecter share one column pair, so {@code approvedById} alone means
     * "decided by", and {@code status} is what separates an approval from a rejection. The two
     * together answer both questions without a schema change.
     *
     * <p>Tenant scoping is the Hibernate {@code orgFilter}, which is fail-closed since issue #507
     * and applies to the count query as well as the rows. The ids here are the caller's, so they
     * are only ever allowed to narrow within the tenant.
     *
     * @param status        Status to return, or null for every status.
     * @param approvedById  Employee id of whoever decided the request, or null for anyone.
     * @param requestedById Employee id of whoever raised the request, or null for anyone.
     * @param pageNo        Zero-based page index.
     * @param pageSize      Rows per page.
     * @return That page of matching requests.
     */
    @Transactional(readOnly = true)
    public Page<AttendanceRegularizationDto> findAll(RegularizationStatus status,
                                                     Long approvedById,
                                                     Long requestedById,
                                                     int pageNo,
                                                     int pageSize) {
        return regularizationRepository
                .findAll(AttendanceRegularizationSpecifications
                                .withFilters(status, approvedById, requestedById),
                        PageRequest.of(pageNo, pageSize, AttendanceRegularizationSpecifications.LIST_ORDER))
                .map(regularizationMapper::toDto);
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
     * <p>A correction let through under the break-glass role says so on every event it writes. The
     * request already records the requester and the approver side by side, but the clock event is
     * what a corrected day is read from, so a correction nobody independent agreed to says so where
     * the day is explained.
     *
     * @param attendance      the record being corrected
     * @param correctedEvents the events to add; {@code null} or empty is a no-op
     * @param org             the owning organization, stamped onto each new event
     * @param selfApproved    whether the approval was a break-glass self-approval
     */
    private void applyCorrectedEvents(Attendance attendance,
                                       List<ClockEventCreationDto> correctedEvents,
                                       Organization org,
                                       boolean selfApproved) {
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
                    .regularizationReason("Self-regularized" + (selfApproved ? SELF_APPROVAL_NOTE : ""))
                    .isWithinGeofence(false)
                    .distanceFromProject(0.0)
                    .organization(org)
                    .build();
            attendance.getClockEvents().add(event);
        }
    }

}
