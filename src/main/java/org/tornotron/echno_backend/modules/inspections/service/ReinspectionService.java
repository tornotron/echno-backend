package org.tornotron.echno_backend.modules.inspections.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.inspections.CheckItemStatus;
import org.tornotron.echno_backend.modules.inspections.DefectStatus;
import org.tornotron.echno_backend.modules.inspections.InspectionOrigin;
import org.tornotron.echno_backend.modules.inspections.InspectionStatus;
import org.tornotron.echno_backend.modules.inspections.NcrStatus;
import org.tornotron.echno_backend.modules.inspections.ReinspectionOutcome;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionCheckItem;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.domain.Ncr;
import org.tornotron.echno_backend.modules.inspections.domain.Reinspection;
import org.tornotron.echno_backend.modules.inspections.dtos.ReinspectionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ReinspectionOutcomeRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ScheduleReinspectionRequest;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventChanges;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventSubject;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventType;
import org.tornotron.echno_backend.modules.inspections.mapper.ReinspectionMapper;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.NcrRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ReinspectionRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reinspection: schedule a re-check of an NCR or a defect, and record what it found.
 *
 * <p>Scheduling creates the record and a new {@link Inspection} in one transaction: same
 * project, type, category and trade as the original, the original's failed check points
 * copied and reset to pending (every check point on request), the named inspector, status
 * scheduled. The inspector then runs it through the normal inspection flow.
 *
 * <p>Recording the outcome is what moves the non-conformance. A failed re-check sends an NCR
 * to rejected through {@link NcrService#reject}, and a defect back to in-progress. A passed
 * re-check verifies a defect outright; for an NCR it is the verify call, carrying the
 * reinspection id, that moves to verified, because that step is role-gated by discipline and
 * this service must not go round the gate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReinspectionService {

    private static final String DOC_TYPE = "INSP";

    private final ReinspectionRepository reinspectionRepo;
    private final NcrRepository ncrRepo;
    private final InspectionRepository inspectionRepo;
    private final NcrService ncrService;
    private final EmployeeRepository employeeRepository;
    private final UserContextService userContextService;
    private final EntryNumberGenerator numberGen;
    private final TenantEntityHelper tenantEntityHelper;
    private final ReinspectionMapper mapper;
    private final InspectionEventRecorder events;

    @Transactional(readOnly = true)
    public ReinspectionDto findById(UUID id) {
        return mapper.toDto(require(id));
    }

    @Transactional(readOnly = true)
    public List<ReinspectionDto> findByNcr(UUID ncrId) {
        requireNcr(ncrId);
        return reinspectionRepo.findByNcrIdAndOrganization_IdOrderBySequenceAsc(ncrId, orgId())
                .stream().map(mapper::toDto).toList();
    }

    /**
     * Schedules a re-check of an NCR awaiting verification.
     *
     * @throws InvalidRequestException if the NCR is not at corrective-action-complete, or it
     *                                 already has a pending attempt
     */
    @Transactional
    public ReinspectionDto scheduleForNcr(UUID ncrId, ScheduleReinspectionRequest req) {
        Ncr ncr = requireNcr(ncrId);
        if (ncr.getStatus() != NcrStatus.CORRECTIVE_ACTION_COMPLETE) {
            throw new InvalidRequestException("NCR " + ncr.getNcrNumber() + " is "
                    + ncr.getStatus().getValue() + "; a reinspection can be scheduled only once the "
                    + "corrective action is reported complete.");
        }
        Inspection original = requireInspection(ncr.getInspectionId());
        int sequence = (int) reinspectionRepo.countByNcrIdAndOrganization_Id(ncrId, orgId()) + 1;
        requireNoPendingAttempt(reinspectionRepo.findByNcrIdAndOrganization_IdOrderBySequenceAsc(ncrId, orgId()),
                "NCR " + ncr.getNcrNumber());
        return schedule(original, ncr.getId(), ncr.getDefectId(), sequence,
                "Reinspection " + sequence + " of " + ncr.getNcrNumber(), req);
    }

    /**
     * Schedules a re-check of a defect reported resolved, with no NCR over it.
     *
     * @throws InvalidRequestException if the defect is not resolved, or already has a pending attempt
     */
    @Transactional
    public ReinspectionDto scheduleForDefect(UUID defectId, ScheduleReinspectionRequest req) {
        InspectionDefect defect = requireDefect(defectId);
        if (defect.getStatus() != DefectStatus.RESOLVED) {
            throw new InvalidRequestException("Defect " + defectId + " is "
                    + defect.getStatus().getValue() + "; a reinspection can be scheduled only once it "
                    + "is reported resolved.");
        }
        Inspection original = defect.getInspection();
        int sequence = (int) reinspectionRepo.countByDefectIdAndOrganization_Id(defectId, orgId()) + 1;
        requireNoPendingAttempt(
                reinspectionRepo.findByDefectIdAndOrganization_IdOrderBySequenceAsc(defectId, orgId()),
                "defect " + defectId);
        return schedule(original, null, defect.getId(), sequence,
                "Reinspection " + sequence + " of defect on " + original.getInspectionNumber(), req);
    }

    /**
     * Records what the re-check found and moves the non-conformance accordingly.
     *
     * @throws InvalidRequestException if the outcome is pending, or was already recorded
     */
    @Transactional
    public ReinspectionDto recordOutcome(UUID id, ReinspectionOutcomeRequest req) {
        Reinspection reinspection = require(id);
        if (req.outcome() == ReinspectionOutcome.PENDING) {
            throw new InvalidRequestException("A reinspection outcome is passed or failed; pending is "
                    + "what it is before one is recorded.");
        }
        if (reinspection.getOutcome() != ReinspectionOutcome.PENDING) {
            throw new InvalidRequestException("Reinspection " + id + " already has the outcome "
                    + reinspection.getOutcome().getValue() + ". A further attempt is a new reinspection.");
        }
        Long by = currentEmployeeId();
        reinspection.setOutcome(req.outcome());
        reinspection.setOutcomeById(by);
        reinspection.setOutcomeAt(LocalDateTime.now());
        reinspection.setRemarks(req.remarks());
        Reinspection saved = reinspectionRepo.saveAndFlush(reinspection);

        events.record(subjectOf(saved), InspectionEventType.REINSPECTION_OUTCOME_RECORDED,
                Map.of("outcome", ReinspectionOutcome.PENDING.getValue()),
                InspectionEventChanges.none()
                        .field("outcome", null, saved.getOutcome())
                        .field("outcomeById", null, by)
                        .after(),
                req.remarks());

        if (saved.getNcrId() != null && saved.getOutcome() == ReinspectionOutcome.FAILED) {
            ncrService.reject(saved.getNcrId(), req.remarks());
        } else if (saved.getNcrId() == null && saved.getDefectId() != null) {
            moveDefect(saved, req.remarks());
        }
        log.info("Reinspection {} attempt {} recorded {}", saved.getId(), saved.getSequence(),
                saved.getOutcome().getValue());
        return mapper.toDto(saved);
    }

    private ReinspectionDto schedule(Inspection original, UUID ncrId, UUID defectId, int sequence,
                                     String title, ScheduleReinspectionRequest req) {
        if (req.assignedInspectorId() != null) {
            requireEmployeeInTenant(req.assignedInspectorId());
        }
        Inspection recheck = cloneForRecheck(original, title, req);
        Inspection savedInspection = inspectionRepo.saveAndFlush(recheck);

        Reinspection reinspection = new Reinspection();
        reinspection.setOrganization(original.getOrganization());
        reinspection.setProjectId(original.getProjectId());
        reinspection.setNcrId(ncrId);
        reinspection.setDefectId(defectId);
        reinspection.setOriginalInspectionId(original.getId());
        reinspection.setReinspectionInspectionId(savedInspection.getId());
        reinspection.setSequence(sequence);
        reinspection.setRequestedById(currentEmployeeId());
        reinspection.setRequestedAt(LocalDateTime.now());
        reinspection.setAssignedInspectorId(req.assignedInspectorId());
        reinspection.setTargetDate(req.targetDate());
        reinspection.setOutcome(ReinspectionOutcome.PENDING);
        Reinspection saved = reinspectionRepo.saveAndFlush(reinspection);

        events.record(InspectionEventSubject.inspection(savedInspection), InspectionEventType.INSPECTION_CREATED,
                null,
                InspectionEventChanges.none()
                        .field("inspectionNumber", null, savedInspection.getInspectionNumber())
                        .field("title", null, savedInspection.getTitle())
                        .field("status", null, savedInspection.getStatus())
                        .field("reinspectionId", null, saved.getId())
                        .after(),
                "Re-check of " + original.getInspectionNumber());
        events.record(subjectOf(saved), InspectionEventType.REINSPECTION_SCHEDULED, null,
                InspectionEventChanges.none()
                        .field("sequence", null, saved.getSequence())
                        .field("ncrId", null, saved.getNcrId())
                        .field("defectId", null, saved.getDefectId())
                        .field("reinspectionInspectionId", null, saved.getReinspectionInspectionId())
                        .field("assignedInspectorId", null, saved.getAssignedInspectorId())
                        .field("targetDate", null, saved.getTargetDate())
                        .field("outcome", null, saved.getOutcome())
                        .after(),
                null);
        log.info("Scheduled reinspection {} (attempt {}) as inspection {}",
                saved.getId(), sequence, savedInspection.getInspectionNumber());
        return mapper.toDto(saved);
    }

    /** The original's header with the check points to be re-run, reset to pending. */
    private Inspection cloneForRecheck(Inspection original, String title, ScheduleReinspectionRequest req) {
        Inspection recheck = new Inspection();
        recheck.setInspectionNumber(numberGen.next(DOC_TYPE));
        recheck.setTitle(title.length() > 200 ? title.substring(0, 200) : title);
        recheck.setType(original.getType());
        recheck.setCategory(original.getCategory());
        recheck.setTrade(original.getTrade());
        recheck.setTradeRef(original.getTradeRef());
        recheck.setStatus(InspectionStatus.SCHEDULED);
        recheck.setOrigin(InspectionOrigin.MANUAL);
        recheck.setProjectId(original.getProjectId());
        recheck.setLocation(original.getLocation());
        recheck.setAreaInspected(original.getAreaInspected());
        recheck.setDrawingReference(original.getDrawingReference());
        recheck.setScheduledDate(req.targetDate());
        recheck.setInspectorId(req.assignedInspectorId());
        recheck.setContractorId(original.getContractorId());
        recheck.setOrganization(original.getOrganization());
        for (InspectionCheckItem source : original.getCheckItems()) {
            if (!req.copyAll() && source.getStatus() != CheckItemStatus.FAILED) {
                continue;
            }
            InspectionCheckItem item = new InspectionCheckItem();
            item.setCategory(source.getCategory());
            item.setCheckPoint(source.getCheckPoint());
            item.setSpecification(source.getSpecification());
            item.setStatus(CheckItemStatus.PENDING);
            item.setPhotosRequired(source.isPhotosRequired());
            item.setExpectedValue(source.getExpectedValue());
            item.setAcceptanceCriterion(source.getAcceptanceCriterion());
            item.setTolerance(source.getTolerance());
            item.setBimElementGuid(source.getBimElementGuid());
            item.setPriority(source.getPriority());
            recheck.addCheckItem(item);
        }
        recheck.setTotalCheckPoints(recheck.getCheckItems().size());
        return recheck;
    }

    /** A passed re-check verifies the defect; a failed one puts the work back in progress. */
    private void moveDefect(Reinspection saved, String remarks) {
        InspectionDefect defect = requireDefect(saved.getDefectId());
        DefectStatus target = saved.getOutcome() == ReinspectionOutcome.PASSED
                ? DefectStatus.VERIFIED : DefectStatus.IN_PROGRESS;
        InspectionEventChanges changes = InspectionEventChanges.none()
                .field("status", defect.getStatus(), target)
                .field("reinspectionId", null, saved.getId());
        defect.setStatus(target);
        if (target == DefectStatus.IN_PROGRESS) {
            changes.field("resolvedDate", defect.getResolvedDate(), null);
            defect.setResolvedDate(null);
        }
        inspectionRepo.saveAndFlush(defect.getInspection());
        events.record(InspectionEventSubject.defect(defect), InspectionEventType.DEFECT_STATUS_CHANGED,
                changes.before(), changes.after(), remarks);
    }

    private static void requireNoPendingAttempt(List<Reinspection> attempts, String what) {
        boolean pending = attempts.stream().anyMatch(r -> r.getOutcome() == ReinspectionOutcome.PENDING);
        if (pending) {
            throw new InvalidRequestException("A reinspection of " + what
                    + " is already scheduled and has no outcome yet; record it before scheduling another.");
        }
    }

    private static InspectionEventSubject subjectOf(Reinspection r) {
        return InspectionEventSubject.reinspection(r.getId(), r.getOriginalInspectionId(), r.getProjectId());
    }

    private void requireEmployeeInTenant(Long employeeId) {
        if (employeeRepository.existsByIdAndOrganization_Id(employeeId, orgId())) {
            return;
        }
        throw new ResourceNotFoundException("Employee with ID " + employeeId
                + " was not found in this organization and cannot be assigned a reinspection");
    }

    private Long currentEmployeeId() {
        Long userId = userContextService.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return employeeRepository.findByUserIdAndOrganizationId(userId, orgId())
                .map(Employee::getId)
                .orElse(null);
    }

    private static Long orgId() {
        return TenantContext.getCurrentOrgId();
    }

    private Reinspection require(UUID id) {
        return reinspectionRepo.findByIdScoped(id)
                .filter(r -> r.getOrganization().getId().equals(orgId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reinspection with ID " + id + " was not found"));
    }

    private Ncr requireNcr(UUID ncrId) {
        return ncrRepo.findByIdScoped(ncrId)
                .filter(n -> n.getOrganization().getId().equals(orgId()))
                .orElseThrow(() -> new ResourceNotFoundException("NCR with ID " + ncrId + " was not found"));
    }

    private Inspection requireInspection(UUID id) {
        return inspectionRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection with ID " + id + " was not found"));
    }

    private InspectionDefect requireDefect(UUID defectId) {
        return inspectionRepo.findDefectByIdAndOrganizationId(defectId, orgId())
                .orElseThrow(() -> new ResourceNotFoundException("Defect with ID " + defectId + " was not found"));
    }
}
