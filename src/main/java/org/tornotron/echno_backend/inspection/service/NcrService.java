package org.tornotron.echno_backend.inspection.service;

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
import org.tornotron.echno_backend.inspection.NcrStatus;
import org.tornotron.echno_backend.inspection.NcrType;
import org.tornotron.echno_backend.inspection.domain.Inspection;
import org.tornotron.echno_backend.inspection.domain.InspectionDefect;
import org.tornotron.echno_backend.inspection.domain.Ncr;
import org.tornotron.echno_backend.inspection.dtos.AssignNcrRequest;
import org.tornotron.echno_backend.inspection.dtos.CreateNcrRequest;
import org.tornotron.echno_backend.inspection.dtos.NcrDto;
import org.tornotron.echno_backend.inspection.mapper.NcrMapper;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.inspection.repositories.NcrRepository;
import org.tornotron.echno_backend.inspection.repositories.NcrSpecifications;
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
        log.info("Raised {} NCR {} against inspection {}",
                saved.getType().getValue(), saved.getNcrNumber(), inspection.getInspectionNumber());
        return mapper.toDto(saved);
    }

    /** Hands the corrective work to a site engineer, or moves it to a different one. */
    @Transactional
    public NcrDto assign(UUID id, AssignNcrRequest req) {
        Ncr ncr = require(id);
        requireEmployeeInTenant(req.siteEngineerId());
        transition(ncr, NcrStatus.ASSIGNED);
        ncr.setSiteEngineerId(req.siteEngineerId());
        if (req.targetDate() != null) {
            ncr.setTargetDate(req.targetDate());
        }
        return save(ncr, "assigned to employee " + req.siteEngineerId());
    }

    /**
     * The site engineer reports the corrective work done and the NCR ready for
     * re-inspection. This is as far as the assignee can take it: accepting the work
     * is somebody else's decision.
     */
    @Transactional
    public NcrDto markCorrectiveActionComplete(UUID id, String remarks) {
        Ncr ncr = require(id);
        transition(ncr, NcrStatus.CORRECTIVE_ACTION_COMPLETE);
        ncr.setCorrectiveActionRemarks(remarks);
        ncr.setCorrectiveActionCompletedAt(LocalDateTime.now());
        return save(ncr, "corrective action reported complete");
    }

    /** Re-inspected and accepted. Closing it is a separate, role-gated step. */
    @Transactional
    public NcrDto verify(UUID id, String remarks) {
        Ncr ncr = require(id);
        transition(ncr, NcrStatus.VERIFIED);
        ncr.setVerificationRemarks(remarks);
        ncr.setVerifiedById(currentEmployeeId());
        ncr.setVerifiedAt(LocalDateTime.now());
        return save(ncr, "verified");
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
        transition(ncr, NcrStatus.REJECTED);
        ncr.setVerificationRemarks(remarks);
        ncr.setVerifiedById(currentEmployeeId());
        ncr.setVerifiedAt(LocalDateTime.now());
        return save(ncr, "rejected on re-inspection");
    }

    /**
     * The same non-conformance has come back. It is recorded on the original NCR
     * rather than as a new one, because the first report's history is the evidence
     * that it recurred.
     */
    @Transactional
    public NcrDto reopen(UUID id, String remarks) {
        Ncr ncr = require(id);
        transition(ncr, NcrStatus.REOPENED);
        ncr.setVerificationRemarks(remarks);
        ncr.setClosedById(null);
        ncr.setClosedAt(null);
        return save(ncr, "reopened");
    }

    /** Closes a verified NCR and records who closed it. */
    @Transactional
    public NcrDto close(UUID id) {
        Ncr ncr = require(id);
        transition(ncr, NcrStatus.CLOSED);
        ncr.setClosedById(currentEmployeeId());
        ncr.setClosedAt(LocalDateTime.now());
        return save(ncr, "closed");
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

    private NcrDto save(Ncr ncr, String what) {
        Ncr saved = ncrRepo.saveAndFlush(ncr);
        log.info("NCR {} {}", saved.getNcrNumber(), what);
        return mapper.toDto(saved);
    }
}
