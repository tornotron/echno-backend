package org.tornotron.echno_backend.modules.inspections.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.inspections.NcrStatus;
import org.tornotron.echno_backend.modules.inspections.NcrType;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.domain.Ncr;
import org.tornotron.echno_backend.modules.inspections.dtos.AssignNcrRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateNcrRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.NcrDto;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventChanges;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventSubject;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventType;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapper;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.NcrRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.NcrSpecifications;
import org.tornotron.echno_backend.modules.inspections.repositories.ReinspectionRepository;
import org.tornotron.echno_backend.modules.inspections.ReinspectionOutcome;
import org.tornotron.echno_backend.modules.inspections.domain.Reinspection;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The non-conformance workflow: raise, assign, report complete, verify, close,
 * with reject and reopen as the ways off the straight line.
 *
 * <p>Every state change goes through {@link #transition}, which asks
 * {@link NcrStatus#canTransitionTo} first. Nothing here sets the status directly,
 * which is the point: an NCR that could be closed straight from open would make
 * the closure trail a record of nothing.
 *
 * <p>The type is taken from the originating inspection's category rather than the
 * request, so a quality NCR cannot be raised from a safety inspection by sending
 * the wrong value. That matters because the type is what decides who may close
 * the NCR.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NcrService {

    private static final String DOC_TYPE = "NCR";

    private final NcrRepository ncrRepo;
    private final InspectionRepository inspectionRepo;
    private final EmployeeRepository employeeRepository;
    private final UserContextService userContextService;
    private final EntryNumberGenerator numberGen;
    private final NcrMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final InspectionEventRecorder events;
    private final ReinspectionRepository reinspectionRepo;

    @Transactional(readOnly = true)
    public NcrDto findById(UUID id) {
        return mapper.toDto(require(id));
    }

    /**
     * The NCR register, and with {@code open} the punch list the functional spec
     * asks for: every non-conformance still outstanding, across inspections.
     *
     * <p>{@code siteEngineerId}, {@code raisedById}, {@code verifiedById} and
     * {@code closedById} are all employee ids, not user ids: every one of them is
     * written from {@link #currentEmployeeId()} or from an assignment that was
     * checked against this organization's employees. They answer the question a QA
     * lead actually asks of a person, which is which reports they raised, accepted
     * or closed, and they narrow the page server-side because narrowing it in the
     * browser would filter one page of a paged result and quietly drop every match
     * outside it.
     */
    @Transactional(readOnly = true)
    public Page<NcrDto> findAll(UUID inspectionId,
                                NcrType type,
                                NcrStatus status,
                                Long siteEngineerId,
                                Long raisedById,
                                Long verifiedById,
                                Long closedById,
                                Boolean open,
                                Pageable pageable) {
        return ncrRepo.findAll(
                        NcrSpecifications.withFilters(inspectionId, type, status, siteEngineerId,
                                raisedById, verifiedById, closedById, open),
                        pageable)
                .map(mapper::toDto);
    }

    /**
     * Raises a non-conformance against an inspection, and assigns it in the same
     * step when a site engineer is named.
     *
     * @throws ResourceNotFoundException if the inspection is not in this tenant, or
     *                                   the named defect does not belong to it.
     */
    @Transactional
    public NcrDto create(CreateNcrRequest req) {
        Inspection inspection = inspectionRepo.findByIdScoped(req.inspectionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inspection with ID " + req.inspectionId() + " was not found"));
        requireDefectBelongsToInspection(inspection, req.defectId());

        Ncr ncr = new Ncr();
        ncr.setNcrNumber(numberGen.next(DOC_TYPE));
        ncr.setType(NcrType.forCategory(inspection.getCategory()));
        ncr.setInspectionId(inspection.getId());
        ncr.setDefectId(req.defectId());
        ncr.setTitle(req.title());
        ncr.setDescription(req.description());
        ncr.setSeverity(req.severity());
        ncr.setTargetDate(req.targetDate());
        ncr.setRaisedById(currentEmployeeId());
        ncr.setStatus(NcrStatus.OPEN);
        ncr.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        if (req.siteEngineerId() != null) {
            requireEmployeeInTenant(req.siteEngineerId());
            transition(ncr, NcrStatus.ASSIGNED);
            ncr.setSiteEngineerId(req.siteEngineerId());
        }

        Ncr saved = ncrRepo.saveAndFlush(ncr);
        events.record(InspectionEventSubject.ncr(saved, inspection.getProjectId()),
                InspectionEventType.NCR_CREATED, null,
                InspectionEventChanges.none()
                        .field("ncrNumber", null, saved.getNcrNumber())
                        .field("type", null, saved.getType())
                        .field("status", null, saved.getStatus())
                        .field("title", null, saved.getTitle())
                        .field("severity", null, saved.getSeverity())
                        .field("defectId", null, saved.getDefectId())
                        .field("siteEngineerId", null, saved.getSiteEngineerId())
                        .field("targetDate", null, saved.getTargetDate())
                        .after(),
                null);
        log.info("Raised {} NCR {} against inspection {}",
                saved.getType().getValue(), saved.getNcrNumber(), inspection.getInspectionNumber());
        return mapper.toDto(saved);
    }

    /** Hands the corrective work to a site engineer, or moves it to a different one. */
    @Transactional
    public NcrDto assign(UUID id, AssignNcrRequest req) {
        Ncr ncr = require(id);
        requireEmployeeInTenant(req.siteEngineerId());
        InspectionEventChanges changes = InspectionEventChanges.none()
                .field("status", ncr.getStatus(), NcrStatus.ASSIGNED)
                .field("siteEngineerId", ncr.getSiteEngineerId(), req.siteEngineerId());
        transition(ncr, NcrStatus.ASSIGNED);
        ncr.setSiteEngineerId(req.siteEngineerId());
        if (req.targetDate() != null) {
            changes.field("targetDate", ncr.getTargetDate(), req.targetDate());
            ncr.setTargetDate(req.targetDate());
        }
        return save(ncr, "assigned to employee " + req.siteEngineerId(),
                InspectionEventType.NCR_ASSIGNED, changes, null);
    }

    /**
     * The site engineer reports the corrective work done and the NCR ready for
     * re-inspection. This is as far as the assignee can take it: accepting the work
     * is somebody else's decision.
     */
    @Transactional
    public NcrDto markCorrectiveActionComplete(UUID id, String remarks) {
        Ncr ncr = require(id);
        LocalDateTime completedAt = LocalDateTime.now();
        InspectionEventChanges changes = InspectionEventChanges.none()
                .field("status", ncr.getStatus(), NcrStatus.CORRECTIVE_ACTION_COMPLETE)
                .field("correctiveActionRemarks", ncr.getCorrectiveActionRemarks(), remarks)
                .field("correctiveActionCompletedAt", ncr.getCorrectiveActionCompletedAt(), completedAt);
        transition(ncr, NcrStatus.CORRECTIVE_ACTION_COMPLETE);
        ncr.setCorrectiveActionRemarks(remarks);
        ncr.setCorrectiveActionCompletedAt(completedAt);
        return save(ncr, "corrective action reported complete",
                InspectionEventType.NCR_CORRECTIVE_ACTION_COMPLETE, changes, remarks);
    }

    /**
     * Re-inspected and accepted. Closing it is a separate, role-gated step.
     *
     * <p>Logged as {@code ncr.verified.without_reinspection}: nothing in the record says which
     * check points were re-run, so the event type names that gap and lets it be reported on.
     */
    @Transactional
    public NcrDto verify(UUID id, String remarks) {
        return verify(id, remarks, null);
    }

    /**
     * Verifies on the strength of a passed reinspection when one is named: the verifier and
     * the time are taken from its outcome, and the event is {@code ncr.verified}. Without one,
     * the verification stands on the caller alone and is logged as such.
     *
     * @throws InvalidRequestException if the reinspection is not this NCR's, or has not passed
     */
    @Transactional
    public NcrDto verify(UUID id, String remarks, UUID reinspectionId) {
        Ncr ncr = require(id);
        if (reinspectionId == null) {
            LocalDateTime decidedAt = LocalDateTime.now();
            InspectionEventChanges changes = verificationChanges(ncr, NcrStatus.VERIFIED, remarks, decidedAt);
            transition(ncr, NcrStatus.VERIFIED);
            stampVerification(ncr, remarks, decidedAt);
            return save(ncr, "verified", InspectionEventType.NCR_VERIFIED_WITHOUT_REINSPECTION,
                    changes, remarks);
        }
        Reinspection reinspection = reinspectionRepo.findByIdScoped(reinspectionId)
                .filter(r -> ncr.getId().equals(r.getNcrId()))
                .orElseThrow(() -> new InvalidRequestException("Reinspection " + reinspectionId
                        + " does not belong to NCR " + ncr.getNcrNumber() + "."));
        if (reinspection.getOutcome() != ReinspectionOutcome.PASSED) {
            throw new InvalidRequestException("Reinspection " + reinspectionId + " is "
                    + reinspection.getOutcome().getValue() + "; only a passed reinspection verifies "
                    + "NCR " + ncr.getNcrNumber() + ".");
        }
        Long verifier = reinspection.getOutcomeById() != null ? reinspection.getOutcomeById() : currentEmployeeId();
        LocalDateTime verifiedAt = reinspection.getOutcomeAt() != null ? reinspection.getOutcomeAt() : LocalDateTime.now();
        InspectionEventChanges changes = InspectionEventChanges.none()
                .field("status", ncr.getStatus(), NcrStatus.VERIFIED)
                .field("verificationRemarks", ncr.getVerificationRemarks(), remarks)
                .field("verifiedById", ncr.getVerifiedById(), verifier)
                .field("verifiedAt", ncr.getVerifiedAt(), verifiedAt)
                .field("reinspectionId", null, reinspection.getId());
        transition(ncr, NcrStatus.VERIFIED);
        ncr.setVerificationRemarks(remarks);
        ncr.setVerifiedById(verifier);
        ncr.setVerifiedAt(verifiedAt);
        return save(ncr, "verified on reinspection " + reinspection.getSequence(),
                InspectionEventType.NCR_VERIFIED, changes, remarks);
    }

    /**
     * Re-inspected and not accepted: it goes back to the site engineer.
     *
     * <p>Stamps the same three fields an acceptance does. They record the last
     * re-inspection decision rather than an acceptance, and a rejection is one:
     * leaving the time behind would either show a rejecting engineer with no date,
     * or, on a report that had been accepted and reopened once already, show the
     * rejection against the date of the earlier acceptance.
     */
    @Transactional
    public NcrDto reject(UUID id, String remarks) {
        Ncr ncr = require(id);
        LocalDateTime decidedAt = LocalDateTime.now();
        InspectionEventChanges changes = verificationChanges(ncr, NcrStatus.REJECTED, remarks, decidedAt);
        transition(ncr, NcrStatus.REJECTED);
        stampVerification(ncr, remarks, decidedAt);
        return save(ncr, "rejected on re-inspection", InspectionEventType.NCR_REJECTED,
                changes, remarks);
    }

    /**
     * The same non-conformance has come back. It is recorded on the original NCR
     * rather than as a new one, because the first report's history is the evidence
     * that it recurred.
     */
    @Transactional
    public NcrDto reopen(UUID id, String remarks) {
        Ncr ncr = require(id);
        InspectionEventChanges changes = InspectionEventChanges.none()
                .field("status", ncr.getStatus(), NcrStatus.REOPENED)
                .field("verificationRemarks", ncr.getVerificationRemarks(), remarks)
                .field("closedById", ncr.getClosedById(), null)
                .field("closedAt", ncr.getClosedAt(), null);
        transition(ncr, NcrStatus.REOPENED);
        ncr.setVerificationRemarks(remarks);
        ncr.setClosedById(null);
        ncr.setClosedAt(null);
        return save(ncr, "reopened", InspectionEventType.NCR_REOPENED, changes, remarks);
    }

    /** Closes a verified NCR and records who closed it. */
    @Transactional
    public NcrDto close(UUID id) {
        Ncr ncr = require(id);
        Long closer = currentEmployeeId();
        LocalDateTime closedAt = LocalDateTime.now();
        InspectionEventChanges changes = InspectionEventChanges.none()
                .field("status", ncr.getStatus(), NcrStatus.CLOSED)
                .field("closedById", ncr.getClosedById(), closer)
                .field("closedAt", ncr.getClosedAt(), closedAt);
        transition(ncr, NcrStatus.CLOSED);
        ncr.setClosedById(closer);
        ncr.setClosedAt(closedAt);
        return save(ncr, "closed", InspectionEventType.NCR_CLOSED, changes, null);
    }

    /**
     * Moves an NCR along its lifecycle, or refuses the move.
     *
     * <p>This is the only place the status is written. Every action calls it before
     * it writes anything else, because a refusal has to leave the report untouched:
     * a rejected close that had already stamped closedAt would leave an open report
     * carrying a closure date, and the caller reads that back inside the same
     * transaction whether or not it is ever committed.
     *
     * <p>A refusal names both ends and what would have been allowed, because the
     * caller is a person deciding what to do next, not a machine retrying.
     *
     * @throws InvalidRequestException if the move is not part of the lifecycle.
     */
    private void transition(Ncr ncr, NcrStatus target) {
        NcrStatus current = ncr.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new InvalidRequestException(
                    "NCR " + ncr.getNcrNumber() + " is " + current.getValue()
                            + " and cannot move to " + target.getValue() + ". From "
                            + current.getValue() + " it may move to " + allowedFrom(current) + ".");
        }
        ncr.setStatus(target);
    }

    private static String allowedFrom(NcrStatus current) {
        if (current.allowedNext().isEmpty()) {
            return "nothing: this is where the report ends";
        }
        return String.join(", ", current.allowedNext().stream().map(NcrStatus::getValue).toList());
    }

    /**
     * Refuses an NCR that names a defect belonging to a different inspection. The
     * two ids arrive independently, so without this an NCR could point at an
     * inspection and a defect that have nothing to do with each other, and the
     * corrective action on the record would be somebody else's.
     */
    private static void requireDefectBelongsToInspection(Inspection inspection, UUID defectId) {
        if (defectId == null) {
            return;
        }
        boolean found = inspection.getDefects().stream()
                .map(InspectionDefect::getId)
                .anyMatch(defectId::equals);
        if (!found) {
            throw new ResourceNotFoundException("Defect with ID " + defectId
                    + " was not found on inspection " + inspection.getInspectionNumber());
        }
    }

    /**
     * Refuses an assignment to an employee this organization does not have.
     *
     * <p>Employee references elsewhere in this module are unchecked scalar ids, and
     * on an inspection that is defensible: the inspector is a label on a record of
     * what happened. On an NCR it is not. The site engineer is the owner of
     * outstanding corrective work, and the whole reason this entity exists is to say
     * who that is. An id belonging to nobody, or to somebody in another
     * organization, produces a report that is open against a person who will never
     * see it, and a punch list that cannot be worked.
     *
     * @param siteEngineerId Id of the employee the work is being assigned to.
     * @throws ResourceNotFoundException if no such employee exists in this tenant.
     */
    private void requireEmployeeInTenant(Long siteEngineerId) {
        if (employeeRepository.existsByIdAndOrganization_Id(
                siteEngineerId, TenantContext.getCurrentOrgId())) {
            return;
        }
        throw new ResourceNotFoundException("Employee with ID " + siteEngineerId
                + " was not found in this organization and cannot be assigned a non-conformance");
    }

    /**
     * The authenticated caller as an employee of the current tenant, or null when
     * they have no employee record. Null rather than a refusal: an organization's
     * bootstrap administrator has no employee profile, and refusing to let them
     * raise or close an NCR would leave a new tenant unable to use the module at
     * all. The trail records who it can.
     */
    private Long currentEmployeeId() {
        Long userId = userContextService.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return employeeRepository.findByUserIdAndOrganizationId(userId, TenantContext.getCurrentOrgId())
                .map(Employee::getId)
                .orElse(null);
    }

    private Ncr require(UUID id) {
        return ncrRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "NCR with ID " + id + " was not found"));
    }

    /** The fields a verification decision moves, captured before the entity is touched. */
    private InspectionEventChanges verificationChanges(Ncr ncr, NcrStatus target, String remarks,
                                                       LocalDateTime decidedAt) {
        return InspectionEventChanges.none()
                .field("status", ncr.getStatus(), target)
                .field("verificationRemarks", ncr.getVerificationRemarks(), remarks)
                .field("verifiedById", ncr.getVerifiedById(), currentEmployeeId())
                .field("verifiedAt", ncr.getVerifiedAt(), decidedAt);
    }

    private void stampVerification(Ncr ncr, String remarks, LocalDateTime decidedAt) {
        ncr.setVerificationRemarks(remarks);
        ncr.setVerifiedById(currentEmployeeId());
        ncr.setVerifiedAt(decidedAt);
    }

    /**
     * Persists the change and records its event in the same transaction. The event is
     * written after the flush so a refused write leaves no event behind it.
     */
    private NcrDto save(Ncr ncr, String what, String eventType,
                        InspectionEventChanges changes, String note) {
        Ncr saved = ncrRepo.saveAndFlush(ncr);
        events.record(InspectionEventSubject.ncr(saved, projectOf(saved)), eventType,
                changes.before(), changes.after(), note);
        log.info("NCR {} {}", saved.getNcrNumber(), what);
        return mapper.toDto(saved);
    }

    private Long projectOf(Ncr ncr) {
        return inspectionRepo.findProjectIdByIdScoped(ncr.getInspectionId()).orElse(null);
    }
}
