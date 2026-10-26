package org.tornotron.echno_backend.modules.inspections.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.SqlStateDetector;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.inspections.CheckItemStatus;
import org.tornotron.echno_backend.modules.inspections.DefectStatus;
import org.tornotron.echno_backend.modules.inspections.InspectionStatus;
import org.tornotron.echno_backend.modules.inspections.ObservationOutcomeKind;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewDecision;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewStatus;
import org.tornotron.echno_backend.modules.inspections.ObservationSource;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionCheckItem;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.domain.Ncr;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ReviewObservationRequest;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventActorType;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventChanges;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventSubject;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventType;
import org.tornotron.echno_backend.modules.inspections.mapper.ObservationMapper;
import org.tornotron.echno_backend.modules.inspections.observation.ObservationAlreadyReviewedException;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionSpecifications;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationSpecifications;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.modules.inspections.ObservationEvidence;
import org.tornotron.echno_backend.modules.inspections.RegionOrigin;
import org.tornotron.echno_backend.modules.inspections.domain.ObservationRegion;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationRegionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationRegionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.RegionEditOp;
import org.tornotron.echno_backend.modules.inspections.observation.RegionGeometry;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRegionRepository;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.SpatialPathSegment;
import org.tornotron.echno_backend.user.UserContextService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The observation life cycle: proposed, reviewed, outcome linked.
 *
 * <p>Two kinds of caller. The web endpoints create a human observation (accepted on creation:
 * the inspector's act is the decision) and review a pending one. The inspection and NCR
 * services call the {@code record*} entry points from inside their own transaction so that
 * every failed check item, defect and NCR recorded through the existing forms gets a
 * persistent observation without the inspector's workflow changing; those run with
 * {@link Propagation#MANDATORY} for the same reason the event recorder does.
 *
 * <p>An inspection update rebuilds its check items and defects under new ids, so the
 * outcome links are carried over by position, the way the event log diffs the children.
 * A row that was reviewed keeps its link to the rebuilt child; a failed item or a defect
 * with no observation yet gets one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservationService {

    private final ObservationRepository observationRepo;
    private final InspectionRepository inspectionRepo;
    private final SpatialNodeService spatialNodeService;
    private final InspectionEventRecorder events;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final EmployeeRepository employeeRepository;
    private final ObservationMapper mapper;
    private final ProjectRepository projectRepository;
    private final AttachmentRepository attachmentRepository;
    private final TransactionRetryTemplate retryTemplate;
    private final ObservationRegionRepository regionRepo;

    /** Regions one observation may be posted with; a model that sees more posts more observations. */
    static final int MAX_REGIONS = 20;

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public ObservationDto findById(UUID id) {
        return findById(id, false);
    }

    /** One observation with its active regions, and with the retired ones too when asked. */
    @Transactional(readOnly = true)
    public ObservationDto findById(UUID id, boolean includeRetiredRegions) {
        Observation o = require(id);
        List<ObservationRegion> rows = regionRepo.findByObservationScoped(o.getId());
        return withSpatialPath(mapper.toDto(o)).withRegions(rows.stream()
                .filter(r -> includeRetiredRegions || r.isActive())
                .map(ObservationService::toRegionDto)
                .toList());
    }

    @Transactional(readOnly = true)
    public Page<ObservationDto> findAll(Long projectId,
                                        ObservationReviewStatus reviewStatus,
                                        ObservationSource source,
                                        UUID inspectionId,
                                        UUID spatialNodeId,
                                        LocalDateTime from,
                                        LocalDateTime to,
                                        Pageable pageable) {
        String prefix = spatialNodeId == null ? null
                : spatialNodeService.subtreePathPrefix(spatialNodeId).orElse(InspectionSpecifications.NO_MATCH);
        Page<ObservationDto> page = observationRepo.findAll(
                        ObservationSpecifications.withFilters(projectId, reviewStatus, source, inspectionId,
                                prefix, from, to),
                        pageable)
                .map(mapper::toDto);
        // One breadcrumb lookup for the page (two queries), not one per row.
        Map<UUID, List<SpatialPathSegment>> paths = spatialNodeService.pathsOf(
                page.getContent().stream().map(ObservationDto::spatialNodeId).filter(Objects::nonNull).toList());
        // Likewise one query for the page's regions.
        Map<UUID, List<ObservationRegionDto>> regions = new HashMap<>();
        if (!page.isEmpty()) {
            for (ObservationRegion r : regionRepo.findActiveByObservationsScoped(
                    page.getContent().stream().map(ObservationDto::id).toList())) {
                regions.computeIfAbsent(r.getObservationId(), k -> new ArrayList<>()).add(toRegionDto(r));
            }
        }
        return page.map(dto -> {
            ObservationDto withPath = dto.spatialNodeId() == null ? dto
                    : dto.withSpatialPath(paths.getOrDefault(dto.spatialNodeId(), List.of()));
            return withPath.withRegions(regions.getOrDefault(dto.id(), List.of()));
        });
    }

    // -------------------------------------------------------- human producer

    /** An inspector's own finding: created accepted, reporter and reviewer both the caller. */
    @Transactional
    public ObservationDto create(CreateObservationRequest req) {
        Inspection inspection = null;
        if (req.inspectionId() != null) {
            inspection = inspectionRepo.findByIdScoped(req.inspectionId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Inspection with ID " + req.inspectionId() + " was not found"));
            if (inspection.getProjectId() != null && !inspection.getProjectId().equals(req.projectId())) {
                throw new InvalidRequestException("The inspection belongs to a different project");
            }
        }
        requireProject(req.projectId());
        Long employeeId = currentEmployeeId();
        Observation o = newObservation(req.projectId(), inspection == null ? null : inspection.getId(),
                ObservationSource.HUMAN, req.title(), req.description());
        o.setSpatialNodeId(resolveNode(req.projectId(), req.spatialNodeId()));
        o.setLocationNote(req.locationNote());
        o.setObservedAt(req.observedAt() != null ? req.observedAt() : LocalDateTime.now());
        o.setCategory(req.category());
        o.setSuggestedSeverity(req.suggestedSeverity());
        o.setReportedById(employeeId);
        if (req.evidenceAttachmentIds() != null && !req.evidenceAttachmentIds().isEmpty()) {
            List<Map<String, Object>> refs = new ArrayList<>();
            for (Long attachmentId : req.evidenceAttachmentIds()) {
                if (attachmentId == null
                        || attachmentRepository.findByIdAndOrganization_Id(attachmentId, TenantContext.getCurrentOrgId()).isEmpty()) {
                    throw new ResourceNotFoundException("Attachment with ID " + attachmentId + " was not found");
                }
                refs.add(Map.of("attachmentId", attachmentId));
            }
            o.setEvidenceRefs(refs);
        }
        List<NewRegion> regions = prepareRegions(req.regions(), false);
        accept(o, employeeId, ObservationOutcomeKind.NONE, null);
        Observation saved = observationRepo.saveAndFlush(o);
        List<ObservationRegion> stored = insertRegions(saved, regions, RegionOrigin.HUMAN, employeeId);
        recordCreated(saved, null);
        log.info("Recorded human observation {} on project {} with {} regions", saved.getId(), saved.getProjectId(),
                stored.size());
        return withSpatialPath(mapper.toDto(saved)).withRegions(stored.stream().map(ObservationService::toRegionDto).toList());
    }

    // ------------------------------------------------------ machine producers

    /** What intake returns: the row, and whether this call created it. */
    public record IntakeResult(ObservationDto observation, boolean created) {}

    /**
     * A finding from a drone, a robot, a fixed camera or a model, posted by the fleet's
     * service account. Lands pending. Idempotent on the producer's {@code externalRef}: a
     * repeat returns the row already recorded, unchanged. The check is the fast path; the
     * partial unique index on {@code (organization_id, external_ref)} is the guarantee.
     *
     * <p>Two devices retrying one capture at the same moment both pass the check, and the
     * loser's insert is refused by the index. That refusal is not the loser's answer: the
     * transaction is restarted, and the second attempt's check finds the winner's row and
     * returns it as the repeat it is (200, not 500). The restart has to be a fresh
     * transaction, since under serializable isolation the aborted one could not read past
     * its own snapshot, so the work runs through {@link TransactionRetryTemplate} and this
     * method opens no transaction of its own. The same restart answers the serialization
     * abort CockroachDB reports for the race instead of the unique violation.
     */
    public IntakeResult intake(IntakeObservationRequest req) {
        if (req.source() == ObservationSource.HUMAN) {
            throw new InvalidRequestException("Intake is for machine producers; people record through the web endpoint");
        }
        return retryTemplate.execute(
                "ObservationService.intake",
                failure -> SqlStateDetector.carriesSqlState(failure, SqlStateDetector.UNIQUE_VIOLATION),
                () -> intakeOnce(req));
    }

    private IntakeResult intakeOnce(IntakeObservationRequest req) {
        Optional<Observation> existing = observationRepo.findByExternalRefScoped(req.externalRef());
        if (existing.isPresent()) {
            log.debug("Observation intake repeated externalRef {}; returning the existing row", req.externalRef());
            return new IntakeResult(findById(existing.get().getId(), false), false);
        }
        requireProject(req.projectId());
        List<NewRegion> regions = prepareRegions(req.regions(), true);
        Observation o = newObservation(req.projectId(), null, req.source(), req.title(), req.description());
        o.setExternalRef(req.externalRef());
        o.setSourceDeviceId(req.sourceDeviceId());
        o.setMissionRef(req.missionRef());
        o.setCaptureRef(req.captureRef());
        o.setSpatialNodeId(resolveNode(req.projectId(), req.spatialNodeId()));
        o.setLocationNote(req.locationNote());
        o.setObservedAt(req.observedAt());
        o.setCategory(req.category());
        o.setSuggestedSeverity(req.suggestedSeverity());
        o.setModelName(req.modelName());
        o.setModelVersion(req.modelVersion());
        o.setConfidence(req.confidence());
        o.setEvidenceRefs(req.evidenceRefs());
        Observation saved = observationRepo.saveAndFlush(o);
        List<ObservationRegion> stored = insertRegions(saved, regions, RegionOrigin.PRODUCER, null);
        InspectionEventActorType actor = req.source() == ObservationSource.AI
                ? InspectionEventActorType.AI : InspectionEventActorType.DEVICE;
        String actorId = req.source() == ObservationSource.AI && req.modelName() != null
                ? req.modelName() : req.sourceDeviceId();
        events.recordAs(InspectionEventSubject.observation(saved), InspectionEventType.OBSERVATION_CREATED,
                actor, actorId, null,
                InspectionEventChanges.none()
                        .field("source", null, saved.getSource())
                        .field("externalRef", null, saved.getExternalRef())
                        .field("sourceDeviceId", null, saved.getSourceDeviceId())
                        .field("missionRef", null, saved.getMissionRef())
                        .field("modelName", null, saved.getModelName())
                        .field("modelVersion", null, saved.getModelVersion())
                        .field("confidence", null, saved.getConfidence())
                        .field("reviewStatus", null, saved.getReviewStatus())
                        .field("title", null, saved.getTitle())
                        .field("regionCount", null, stored.isEmpty() ? null : stored.size())
                        .after(),
                null);
        log.info("Observation {} taken in from {} {} on project {} (externalRef {}, {} regions)", saved.getId(),
                saved.getSource(), saved.getSourceDeviceId(), saved.getProjectId(), saved.getExternalRef(),
                stored.size());
        return new IntakeResult(withSpatialPath(mapper.toDto(saved))
                .withRegions(stored.stream().map(ObservationService::toRegionDto).toList()), true);
    }

    // ---------------------------------------------------------------- review

    /**
     * The one human decision on a pending observation. Accept and modify link an outcome;
     * reject records the reason and leaves the row as a negative example.
     *
     * @throws ObservationAlreadyReviewedException if the observation already has a decision
     * @throws InvalidRequestException on a reject with no note, a modify with no changes, or an
     *                                 outcome the request does not fully describe
     */
    @Transactional
    public ObservationDto review(UUID id, ReviewObservationRequest req) {
        Observation o = require(id);
        if (o.isReviewed()) {
            throw new ObservationAlreadyReviewedException(id);
        }
        Long reviewerId = currentEmployeeId();
        if (req.rejectReason() != null && req.decision() != ObservationReviewDecision.REJECT) {
            throw new InvalidRequestException("rejectReason is for a REJECT decision only");
        }
        List<ObservationRegion> rows = regionRepo.findByObservationScoped(o.getId());
        RegionEdits regionEdits = null;
        ObservationReviewStatus decided;
        switch (req.decision()) {
            case REJECT -> {
                if (req.note() == null || req.note().isBlank()) {
                    throw new InvalidRequestException("A note is required to reject an observation");
                }
                boolean producerRegions = rows.stream().anyMatch(r -> r.getOrigin() == RegionOrigin.PRODUCER);
                if (producerRegions && req.rejectReason() == null) {
                    throw new InvalidRequestException(
                            "rejectReason is required to reject an observation with producer regions");
                }
                o.setRejectReason(req.rejectReason());
                decided = ObservationReviewStatus.REJECTED;
            }
            case ACCEPT -> {
                decided = ObservationReviewStatus.ACCEPTED;
                applyOutcome(o, req.outcome());
            }
            case MODIFY -> {
                List<Map<String, Object>> changes = diff(o, req.changes());
                if (req.changes() != null && req.changes().regions() != null && !req.changes().regions().isEmpty()) {
                    regionEdits = applyRegionEdits(o, rows, req.changes().regions(), reviewerId);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("field", "regions");
                    entry.put("before", regionEdits.before());
                    entry.put("after", regionEdits.after());
                    changes.add(entry);
                }
                if (changes.isEmpty()) {
                    throw new InvalidRequestException("MODIFY requires at least one change to the proposal");
                }
                o.setReviewChanges(changes);
                decided = ObservationReviewStatus.MODIFIED;
                applyOutcome(o, req.outcome());
            }
            default -> throw new InvalidRequestException("Unknown review decision");
        }
        o.setReviewStatus(decided);
        o.setReviewedById(reviewerId);
        o.setReviewedAt(LocalDateTime.now());
        o.setReviewNote(req.note());
        Observation saved = observationRepo.saveAndFlush(o);
        recordReviewed(saved, ObservationReviewStatus.PENDING, regionEdits);
        log.info("Observation {} reviewed as {} with outcome {}", saved.getId(), decided, saved.getOutcomeKind());
        return findById(saved.getId(), false);
    }

    private void applyOutcome(Observation o, ReviewObservationRequest.Outcome outcome) {
        ObservationOutcomeKind kind = outcome == null ? ObservationOutcomeKind.NONE : outcome.kind();
        switch (kind) {
            case NONE -> {
                o.setOutcomeKind(ObservationOutcomeKind.NONE);
                o.setOutcomeRef(null);
            }
            case CHECK_ITEM -> markCheckItem(o, outcome);
            case DEFECT -> {
                if (outcome.defectId() != null) {
                    attachDefect(o, outcome.defectId());
                } else if (outcome.defect() != null) {
                    createDefect(o, outcome.defect());
                } else {
                    throw new InvalidRequestException("A DEFECT outcome needs defectId or defect");
                }
            }
            case INSPECTION -> {
                if (outcome.inspectionId() == null) {
                    throw new InvalidRequestException("An INSPECTION outcome needs inspectionId");
                }
                Inspection inspection = requireInspection(outcome.inspectionId());
                requireSameInspection(o, inspection);
                o.setInspectionId(inspection.getId());
                o.setOutcomeKind(ObservationOutcomeKind.INSPECTION);
                o.setOutcomeRef(inspection.getId());
            }
            case NCR -> throw new InvalidRequestException(
                    "An NCR is raised through the NCR endpoint and links its observation itself");
        }
    }

    private void markCheckItem(Observation o, ReviewObservationRequest.Outcome outcome) {
        if (outcome.checkItemId() == null || outcome.status() == null) {
            throw new InvalidRequestException("A CHECK_ITEM outcome needs checkItemId and status");
        }
        if (outcome.status() == CheckItemStatus.PENDING) {
            throw new InvalidRequestException("A check item outcome must be a result, not PENDING");
        }
        Inspection inspection = inspectionRepo.findByCheckItemIdScoped(outcome.checkItemId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Check item with ID " + outcome.checkItemId() + " was not found"));
        requireSameInspection(o, inspection);
        InspectionCheckItem item = inspection.getCheckItems().stream()
                .filter(c -> outcome.checkItemId().equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Check item with ID " + outcome.checkItemId() + " was not found"));
        CheckItemStatus before = item.getStatus();
        item.setStatus(outcome.status());
        if (o.getDescription() != null && (item.getRemarks() == null || item.getRemarks().isBlank())) {
            item.setRemarks(truncate(o.getDescription(), 1000));
        }
        ChecklistGate.requireRemarkWhenNotDone(item.getStatus(), item.getRemarks(),
                item.getCategory(), item.getCheckPoint());
        recount(inspection);
        o.setInspectionId(inspection.getId());
        o.setOutcomeKind(ObservationOutcomeKind.CHECK_ITEM);
        o.setOutcomeRef(item.getId());
        if (before != outcome.status()) {
            InspectionEventChanges result = InspectionEventChanges.none().field("status", before, item.getStatus());
            events.record(InspectionEventSubject.checkItem(item), InspectionEventType.CHECK_ITEM_RESULT_RECORDED,
                    result.before(), result.after(), item.getCheckPoint());
        }
    }

    private void attachDefect(Observation o, UUID defectId) {
        Inspection inspection = inspectionRepo.findByDefectIdScoped(defectId)
                .orElseThrow(() -> new ResourceNotFoundException("Defect with ID " + defectId + " was not found"));
        requireSameInspection(o, inspection);
        InspectionDefect defect = inspection.getDefects().stream()
                .filter(d -> defectId.equals(d.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Defect with ID " + defectId + " was not found"));
        if (defect.getObservationId() != null && !defect.getObservationId().equals(o.getId())) {
            throw new InvalidRequestException("Defect " + defectId + " is already linked to observation "
                    + defect.getObservationId());
        }
        defect.setObservationId(o.getId());
        o.setInspectionId(inspection.getId());
        o.setOutcomeKind(ObservationOutcomeKind.DEFECT);
        o.setOutcomeRef(defect.getId());
    }

    private void createDefect(Observation o, InspectionDefectRequest dr) {
        if (o.getInspectionId() == null) {
            throw new InvalidRequestException(
                    "Creating a defect needs the observation on an inspection; attach one first or pass defectId");
        }
        Inspection inspection = requireInspection(o.getInspectionId());
        InspectionDefect defect = new InspectionDefect();
        defect.setCategory(dr.category());
        defect.setDescription(dr.description());
        defect.setSeverity(dr.severity());
        defect.setLocation(dr.location());
        defect.setSpatialNodeId(dr.spatialNodeId() != null
                ? spatialNodeService.requireUsableNode(inspection.getProjectId(), dr.spatialNodeId()).getId()
                : o.getSpatialNodeId());
        if (dr.photos() != null) {
            defect.getPhotos().addAll(dr.photos());
        }
        defect.setCorrectiveAction(dr.correctiveAction());
        defect.setResponsibleParty(dr.responsibleParty());
        defect.setTargetDate(dr.targetDate());
        defect.setStatus(dr.status() != null ? dr.status() : DefectStatus.OPEN);
        defect.setResolvedDate(dr.resolvedDate());
        defect.setObservationId(o.getId());
        inspection.addDefect(defect);
        inspection.setDefectsFound(inspection.getDefects().size());
        // flush, not save: the inspection is managed, and the cascade persists the new child in
        // place so the instance held here carries its id for the link and the event
        inspectionRepo.flush();
        o.setOutcomeKind(ObservationOutcomeKind.DEFECT);
        o.setOutcomeRef(defect.getId());
        events.record(InspectionEventSubject.defect(defect), InspectionEventType.DEFECT_CREATED, null,
                InspectionEventChanges.none()
                        .field("category", null, defect.getCategory())
                        .field("description", null, defect.getDescription())
                        .field("severity", null, defect.getSeverity())
                        .field("status", null, defect.getStatus())
                        .field("observationId", null, o.getId())
                        .after(),
                defect.getDescription());
    }

    /** The reviewer's edits against the proposal, one entry per field that actually differs. */
    private static List<Map<String, Object>> diff(Observation o, ReviewObservationRequest.Changes c) {
        List<Map<String, Object>> changes = new ArrayList<>();
        if (c == null) {
            return changes;
        }
        change(changes, "title", o.getTitle(), c.title());
        change(changes, "description", o.getDescription(), c.description());
        change(changes, "severity", o.getSuggestedSeverity(), c.severity());
        change(changes, "spatialNodeId", o.getSpatialNodeId(), c.spatialNodeId());
        change(changes, "category", o.getCategory(), c.category());
        return changes;
    }

    private static void change(List<Map<String, Object>> into, String field, Object before, Object after) {
        if (after == null) {
            return;
        }
        Object b = InspectionEventChanges.json(before);
        Object a = InspectionEventChanges.json(after);
        if (!Objects.equals(a, b)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", field);
            entry.put("before", b);
            entry.put("after", a);
            into.add(entry);
        }
    }

    // ------------------------------------------------- the compliance model

    /**
     * One AI observation per compliance suggestion: pending, with the suggested inspection as
     * its outcome and the model's rationale as its description. Called from the generator's
     * own transaction. The event names the generator as the actor, the model in the row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Observation recordAiSuggestion(Inspection suggested, String modelName, String modelVersion,
                                          String actorId) {
        Observation o = newObservation(suggested.getProjectId(), suggested.getId(), ObservationSource.AI,
                suggested.getTitle(), suggested.getAiRationale());
        o.setCategory(suggested.getComplianceRuleRef());
        o.setSpatialNodeId(suggested.getSpatialNodeId());
        o.setLocationNote(suggested.getLocation());
        o.setObservedAt(LocalDateTime.now());
        o.setModelName(modelName);
        o.setModelVersion(modelVersion == null || modelVersion.isBlank() ? null : modelVersion);
        o.setOutcomeKind(ObservationOutcomeKind.INSPECTION);
        o.setOutcomeRef(suggested.getId());
        Observation saved = observationRepo.save(o);
        events.recordAs(InspectionEventSubject.observation(saved), InspectionEventType.OBSERVATION_CREATED,
                InspectionEventActorType.AI, actorId, null,
                InspectionEventChanges.none()
                        .field("source", null, saved.getSource())
                        .field("modelName", null, saved.getModelName())
                        .field("modelVersion", null, saved.getModelVersion())
                        .field("reviewStatus", null, saved.getReviewStatus())
                        .field("outcomeKind", null, saved.getOutcomeKind())
                        .field("outcomeRef", null, saved.getOutcomeRef())
                        .field("complianceRuleRef", null, suggested.getComplianceRuleRef())
                        .after(),
                saved.getDescription());
        return saved;
    }

    /**
     * The human decision on a suggested inspection, taken through the inspection form rather
     * than the observation review: dismissing (cancelling) it rejects the pending AI
     * observation, editing it before approval modifies the observation with the diff, and any
     * other move out of SUGGESTED accepts it. One decision; a suggestion already reviewed is
     * left alone.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Observation> reviewSuggestedInspection(Inspection inspection, InspectionStatus before,
                                                           InspectionEventChanges header) {
        if (before != InspectionStatus.SUGGESTED) {
            return Optional.empty();
        }
        Optional<Observation> pending = observationRepo
                .findByOutcomeScoped(ObservationOutcomeKind.INSPECTION, inspection.getId()).stream()
                .filter(o -> !o.isReviewed())
                .findFirst();
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        Observation o = pending.get();
        boolean edited = header != null && !header.isEmpty();
        ObservationReviewStatus decided;
        if (inspection.getStatus() == InspectionStatus.CANCELLED) {
            decided = ObservationReviewStatus.REJECTED;
            o.setReviewNote("Suggested inspection dismissed");
        } else if (edited) {
            decided = ObservationReviewStatus.MODIFIED;
            List<Map<String, Object>> changes = new ArrayList<>();
            for (Map.Entry<String, Object> e : header.after().entrySet()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("field", e.getKey());
                entry.put("before", header.before().get(e.getKey()));
                entry.put("after", e.getValue());
                changes.add(entry);
            }
            o.setReviewChanges(changes);
            o.setReviewNote(inspection.getStatus() == InspectionStatus.SUGGESTED
                    ? "Suggested inspection edited before approval" : "Suggested inspection edited and approved");
        } else if (inspection.getStatus() != InspectionStatus.SUGGESTED) {
            decided = ObservationReviewStatus.ACCEPTED;
            o.setReviewNote("Suggested inspection approved");
        } else {
            return Optional.empty();
        }
        o.setReviewStatus(decided);
        o.setReviewedById(currentEmployeeId());
        o.setReviewedAt(LocalDateTime.now());
        Observation saved = observationRepo.save(o);
        recordReviewed(saved, ObservationReviewStatus.PENDING);
        return Optional.of(saved);
    }

    // ---------------------------------------------- implicit human producers

    /** Every failed check item and every defect of a freshly created inspection gets an observation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordImplicit(Inspection inspection) {
        for (InspectionCheckItem item : inspection.getCheckItems()) {
            if (item.getStatus() == CheckItemStatus.FAILED) {
                recordFailedCheckItem(item);
            }
        }
        for (InspectionDefect defect : inspection.getDefects()) {
            recordDefect(defect);
        }
    }

    /**
     * After an update rebuilt the children under new ids: carry each existing link over to
     * the child at the same position, then give any failed item or defect still without an
     * observation one. Positional, like the event log's child diff.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void carryOver(List<UUID> oldItemIds, List<UUID> oldDefectIds,
                          List<UUID> oldDefectObservationIds, Inspection saved) {
        // Several observations may point at one child (a drone's and a person's, say), so every
        // one of them follows the child to its new id.
        Map<UUID, List<Observation>> byOutcome = new HashMap<>();
        for (Observation existing : observationRepo.findByInspectionScoped(saved.getId())) {
            if (existing.getOutcomeRef() != null) {
                byOutcome.computeIfAbsent(existing.getOutcomeRef(), k -> new ArrayList<>()).add(existing);
            }
        }
        List<InspectionCheckItem> items = saved.getCheckItems();
        for (int i = 0; i < items.size(); i++) {
            InspectionCheckItem item = items.get(i);
            boolean linked = false;
            if (i < oldItemIds.size()) {
                for (Observation o : byOutcome.getOrDefault(oldItemIds.get(i), List.of())) {
                    if (o.getOutcomeKind() == ObservationOutcomeKind.CHECK_ITEM) {
                        o.setOutcomeRef(item.getId());
                        linked = true;
                    }
                }
            }
            if (!linked && item.getStatus() == CheckItemStatus.FAILED) {
                recordFailedCheckItem(item);
            }
        }
        List<InspectionDefect> defects = saved.getDefects();
        for (int i = 0; i < defects.size(); i++) {
            InspectionDefect defect = defects.get(i);
            if (i < oldDefectIds.size() && oldDefectObservationIds.get(i) != null) {
                defect.setObservationId(oldDefectObservationIds.get(i));
                for (Observation o : byOutcome.getOrDefault(oldDefectIds.get(i), List.of())) {
                    if (o.getOutcomeKind() == ObservationOutcomeKind.DEFECT) {
                        o.setOutcomeRef(defect.getId());
                    }
                }
            }
            if (defect.getObservationId() == null) {
                recordDefect(defect);
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Observation recordFailedCheckItem(InspectionCheckItem item) {
        Inspection inspection = item.getInspection();
        Long employeeId = currentEmployeeId();
        Observation o = newObservation(inspection.getProjectId(), inspection.getId(), ObservationSource.HUMAN,
                item.getCheckPoint(), item.getRemarks());
        o.setCategory(item.getCategory());
        o.setSpatialNodeId(item.getSpatialNodeId() != null ? item.getSpatialNodeId() : inspection.getSpatialNodeId());
        o.setLocationNote(inspection.getLocation());
        o.setObservedAt(LocalDateTime.now());
        o.setReportedById(employeeId);
        accept(o, employeeId, ObservationOutcomeKind.CHECK_ITEM, item.getId());
        Observation saved = observationRepo.save(o);
        recordCreated(saved, item.getCheckPoint());
        return saved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Observation recordDefect(InspectionDefect defect) {
        Inspection inspection = defect.getInspection();
        Long employeeId = currentEmployeeId();
        Observation o = newObservation(inspection.getProjectId(), inspection.getId(), ObservationSource.HUMAN,
                defect.getDescription(), defect.getDescription());
        o.setCategory(defect.getCategory());
        o.setSuggestedSeverity(defect.getSeverity());
        o.setSpatialNodeId(defect.getSpatialNodeId() != null ? defect.getSpatialNodeId() : inspection.getSpatialNodeId());
        o.setLocationNote(defect.getLocation() != null ? defect.getLocation() : inspection.getLocation());
        o.setObservedAt(LocalDateTime.now());
        o.setReportedById(employeeId);
        accept(o, employeeId, ObservationOutcomeKind.DEFECT, defect.getId());
        Observation saved = observationRepo.save(o);
        defect.setObservationId(saved.getId());
        recordCreated(saved, defect.getDescription());
        return saved;
    }

    /**
     * An NCR raised from a defect inherits the defect's observation, creating one for the
     * defect first if it predates observations; one raised against the inspection as a whole
     * gets its own, with the NCR as the outcome.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Observation recordNcr(Ncr ncr, Inspection inspection) {
        if (ncr.getDefectId() != null) {
            Optional<InspectionDefect> defect = inspection.getDefects().stream()
                    .filter(d -> ncr.getDefectId().equals(d.getId()))
                    .findFirst();
            if (defect.isPresent()) {
                InspectionDefect d = defect.get();
                Observation o = d.getObservationId() == null
                        ? recordDefect(d)
                        : observationRepo.findByIdScoped(d.getObservationId()).orElseGet(() -> recordDefect(d));
                ncr.setObservationId(o.getId());
                return o;
            }
        }
        Long employeeId = currentEmployeeId();
        Observation o = newObservation(inspection.getProjectId(), inspection.getId(), ObservationSource.HUMAN,
                ncr.getTitle(), ncr.getDescription());
        o.setSuggestedSeverity(ncr.getSeverity());
        o.setSpatialNodeId(inspection.getSpatialNodeId());
        o.setLocationNote(inspection.getLocation());
        o.setObservedAt(LocalDateTime.now());
        o.setReportedById(employeeId);
        accept(o, employeeId, ObservationOutcomeKind.NCR, ncr.getId());
        Observation saved = observationRepo.save(o);
        ncr.setObservationId(saved.getId());
        recordCreated(saved, ncr.getNcrNumber());
        return saved;
    }

    // --------------------------------------------------------------- regions

    /** A region that passed validation and is ready to insert. */
    private record NewRegion(Long attachmentId, RegionGeometry.Normalised geometry, String classKey,
                             BigDecimal score, Integer imageWidth, Integer imageHeight) {}

    /** What a MODIFY did to the regions: active ids before and after, and each op as applied. */
    private record RegionEdits(List<String> before, List<String> after, List<Map<String, Object>> ops) {}

    /**
     * Validates posted regions before anything is written. A producer may send a score; a person
     * may not, since a score is a model's.
     */
    private List<NewRegion> prepareRegions(List<ObservationRegionRequest> requests, boolean producer) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        if (requests.size() > MAX_REGIONS) {
            throw new InvalidRequestException("At most " + MAX_REGIONS + " regions per observation");
        }
        List<NewRegion> prepared = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            ObservationRegionRequest r = requests.get(i);
            String where = "regions[" + i + "]";
            if (r == null) {
                throw new InvalidRequestException(where + ": region is missing");
            }
            if (!producer && r.score() != null) {
                throw new InvalidRequestException(where + ": a region a person draws carries no score");
            }
            if (r.score() != null && (r.score().signum() < 0 || r.score().compareTo(BigDecimal.ONE) > 0)) {
                throw new InvalidRequestException(where + ": score must be in [0, 1]");
            }
            requireRegionImage(where, r.attachmentId());
            prepared.add(new NewRegion(r.attachmentId(),
                    RegionGeometry.normalise(where, r.shape(), r.box(), r.points()),
                    requireClassKey(where, r.classKey()),
                    r.score() == null ? null : r.score().setScale(4, RoundingMode.HALF_UP),
                    requirePositive(where, "imageWidth", r.imageWidth()),
                    requirePositive(where, "imageHeight", r.imageHeight())));
        }
        return prepared;
    }

    private List<ObservationRegion> insertRegions(Observation o, List<NewRegion> regions, RegionOrigin origin,
                                                  Long createdById) {
        List<ObservationRegion> stored = new ArrayList<>(regions.size());
        int order = 0;
        for (NewRegion r : regions) {
            stored.add(regionRepo.save(newRegion(o, r, origin, createdById, order++)));
        }
        return stored;
    }

    private ObservationRegion newRegion(Observation o, NewRegion r, RegionOrigin origin, Long createdById,
                                        int lineOrder) {
        ObservationRegion row = new ObservationRegion();
        row.setOrganization(o.getOrganization());
        row.setObservationId(o.getId());
        row.setAttachmentId(r.attachmentId());
        row.setShape(r.geometry().shape());
        row.setX1(r.geometry().x1());
        row.setY1(r.geometry().y1());
        row.setX2(r.geometry().x2());
        row.setY2(r.geometry().y2());
        row.setPoints(r.geometry().points());
        row.setImageWidth(r.imageWidth());
        row.setImageHeight(r.imageHeight());
        row.setClassKey(r.classKey());
        row.setScore(r.score());
        row.setOrigin(origin);
        row.setLineOrder(lineOrder);
        row.setCreatedById(createdById);
        return row;
    }

    /**
     * A reviewer's region ops, in order. Rows are never changed in place: replace and remove
     * retire the row they name, replace and add insert a {@link RegionOrigin#REVIEWER} row. An add
     * may only draw on an image the observation already cites, so a reviewer cannot pull an
     * unrelated photo of the organisation into the decision.
     */
    private RegionEdits applyRegionEdits(Observation o, List<ObservationRegion> rows, List<RegionEditOp> ops,
                                         Long reviewerId) {
        Map<UUID, ObservationRegion> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.getId(), r));
        List<String> before = activeIds(rows);
        boolean hadProducerRegions = rows.stream()
                .anyMatch(r -> r.isActive() && r.getOrigin() == RegionOrigin.PRODUCER);
        int nextOrder = rows.stream().mapToInt(ObservationRegion::getLineOrder).max().orElse(-1) + 1;
        Set<Long> cited = null;
        List<ObservationRegion> current = new ArrayList<>(rows);
        List<Map<String, Object>> applied = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < ops.size(); i++) {
            RegionEditOp op = ops.get(i);
            String where = "changes.regions[" + i + "]";
            if (op == null || op.op() == null) {
                throw new InvalidRequestException(where + ": op is required");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("op", op.op().getValue());
            switch (op.op()) {
                case REPLACE -> {
                    ObservationRegion target = activeRegion(where, byId, op.regionId());
                    if (op.attachmentId() != null && !op.attachmentId().equals(target.getAttachmentId())) {
                        throw new InvalidRequestException(where + ": a replacement stays on the image of the region it replaces");
                    }
                    if (op.shape() != target.getShape()) {
                        throw new InvalidRequestException(where + ": a replacement keeps the shape of the region it "
                                + "replaces (" + target.getShape().getValue() + ")");
                    }
                    NewRegion r = new NewRegion(target.getAttachmentId(),
                            RegionGeometry.normalise(where, op.shape(), op.box(), op.points()),
                            requireClassKey(where, op.classKey()), null,
                            target.getImageWidth(), target.getImageHeight());
                    if (sameRegion(target, r)) {
                        throw new InvalidRequestException(where + ": the replacement is identical to region "
                                + target.getId());
                    }
                    retire(target, reviewerId, now);
                    ObservationRegion replacement = newRegion(o, r, RegionOrigin.REVIEWER, reviewerId,
                            target.getLineOrder());
                    replacement.setSupersedesId(target.getId());
                    regionRepo.save(replacement);
                    byId.put(replacement.getId(), replacement);
                    current.add(replacement);
                    entry.put("regionId", target.getId().toString());
                    entry.put("newRegionId", replacement.getId().toString());
                    entry.put("classKey", replacement.getClassKey());
                    entry.put("box", RegionGeometry.boxOf(replacement));
                }
                case REMOVE -> {
                    ObservationRegion target = activeRegion(where, byId, op.regionId());
                    retire(target, reviewerId, now);
                    entry.put("regionId", target.getId().toString());
                }
                case ADD -> {
                    if (op.regionId() != null) {
                        throw new InvalidRequestException(where + ": add creates a region; it takes no regionId");
                    }
                    requireRegionImage(where, op.attachmentId());
                    if (cited == null) {
                        cited = citedAttachments(o, rows);
                    }
                    if (!cited.contains(op.attachmentId())) {
                        throw new InvalidRequestException(where + ": attachment " + op.attachmentId()
                                + " is not evidence of this observation");
                    }
                    NewRegion r = new NewRegion(op.attachmentId(),
                            RegionGeometry.normalise(where, op.shape(), op.box(), op.points()),
                            requireClassKey(where, op.classKey()), null,
                            requirePositive(where, "imageWidth", op.imageWidth()),
                            requirePositive(where, "imageHeight", op.imageHeight()));
                    ObservationRegion added = regionRepo.save(newRegion(o, r, RegionOrigin.REVIEWER, reviewerId,
                            nextOrder++));
                    byId.put(added.getId(), added);
                    current.add(added);
                    entry.put("newRegionId", added.getId().toString());
                    entry.put("attachmentId", added.getAttachmentId());
                    entry.put("classKey", added.getClassKey());
                    entry.put("box", RegionGeometry.boxOf(added));
                }
            }
            applied.add(entry);
        }
        List<String> after = activeIds(current);
        if (hadProducerRegions && after.isEmpty()) {
            throw new InvalidRequestException(
                    "A MODIFY cannot remove every region of a producer's observation; reject the observation instead");
        }
        return new RegionEdits(before, after, applied);
    }

    private static ObservationRegion activeRegion(String where, Map<UUID, ObservationRegion> byId, UUID regionId) {
        if (regionId == null) {
            throw new InvalidRequestException(where + ": regionId is required");
        }
        ObservationRegion target = byId.get(regionId);
        if (target == null) {
            throw new ResourceNotFoundException("Region with ID " + regionId + " was not found on this observation");
        }
        if (!target.isActive()) {
            throw new InvalidRequestException(where + ": region " + regionId + " has already been replaced or removed");
        }
        return target;
    }

    private static void retire(ObservationRegion region, Long reviewerId, LocalDateTime when) {
        region.setRetiredAt(when);
        region.setRetiredById(reviewerId);
    }

    private static boolean sameRegion(ObservationRegion row, NewRegion r) {
        return row.getClassKey().equals(r.classKey())
                && row.getX1().compareTo(r.geometry().x1()) == 0
                && row.getY1().compareTo(r.geometry().y1()) == 0
                && row.getX2().compareTo(r.geometry().x2()) == 0
                && row.getY2().compareTo(r.geometry().y2()) == 0
                && Objects.equals(RegionGeometry.pointsKey(row.getPoints()), RegionGeometry.pointsKey(r.geometry().points()));
    }

    private static List<String> activeIds(List<ObservationRegion> rows) {
        return rows.stream()
                .filter(ObservationRegion::isActive)
                .sorted(Comparator.comparingInt(ObservationRegion::getLineOrder))
                .map(r -> r.getId().toString())
                .toList();
    }

    /** The images an observation already cites: its regions, its evidenceRefs ids, and its own uploads. */
    private Set<Long> citedAttachments(Observation o, List<ObservationRegion> rows) {
        Set<Long> ids = new HashSet<>();
        rows.forEach(r -> ids.add(r.getAttachmentId()));
        if (o.getEvidenceRefs() != null) {
            for (Map<String, Object> ref : o.getEvidenceRefs()) {
                if (ref != null && ref.get("attachmentId") instanceof Number n) {
                    ids.add(n.longValue());
                }
            }
        }
        attachmentRepository.findByEntityTypeAndEntityUuidOrderByIdAsc(ObservationEvidence.ENTITY_TYPE, o.getId())
                .forEach(a -> ids.add(a.getId()));
        return ids;
    }

    /** The attachment is in the caller's organisation (404 otherwise) and is an image. */
    private void requireRegionImage(String where, Long attachmentId) {
        if (attachmentId == null) {
            throw new InvalidRequestException(where + ": attachmentId is required");
        }
        Attachment attachment = attachmentRepository
                .findByIdAndOrganization_Id(attachmentId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attachment with ID " + attachmentId + " was not found"));
        String type = attachment.getContentType();
        if (type != null && !type.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new InvalidRequestException(where + ": attachment " + attachmentId + " is " + type
                    + "; regions are drawn on images");
        }
    }

    private static String requireClassKey(String where, String classKey) {
        if (classKey == null || classKey.isBlank()) {
            throw new InvalidRequestException(where + ": classKey is required");
        }
        if (classKey.length() > 50) {
            throw new InvalidRequestException(where + ": classKey is at most 50 characters");
        }
        return classKey;
    }

    private static Integer requirePositive(String where, String field, Integer value) {
        if (value != null && value <= 0) {
            throw new InvalidRequestException(where + ": " + field + " must be positive");
        }
        return value;
    }

    static ObservationRegionDto toRegionDto(ObservationRegion r) {
        return new ObservationRegionDto(r.getId(), r.getAttachmentId(), r.getShape(), RegionGeometry.boxOf(r),
                r.getPoints(), r.getImageWidth(), r.getImageHeight(), r.getClassKey(), r.getScore(), r.getOrigin(),
                r.getSupersedesId(), r.getRetiredAt(), r.getRetiredById(), r.getLineOrder(), r.getCreatedAt(),
                r.getCreatedById());
    }

    // --------------------------------------------------------------- helpers

    Observation newObservation(Long projectId, UUID inspectionId, ObservationSource source,
                               String title, String description) {
        Observation o = new Observation();
        o.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        o.setProjectId(projectId);
        o.setInspectionId(inspectionId);
        o.setSource(source);
        o.setTitle(truncate(title == null || title.isBlank() ? "Observation" : title, 200));
        o.setDescription(description);
        o.setReviewStatus(ObservationReviewStatus.PENDING);
        o.setOutcomeKind(ObservationOutcomeKind.NONE);
        Long userId = userContextService.getCurrentUserId();
        o.setCreatedBy(userId);
        o.setUpdatedBy(userId);
        return o;
    }

    private static void accept(Observation o, Long employeeId, ObservationOutcomeKind kind, UUID ref) {
        o.setReviewStatus(ObservationReviewStatus.ACCEPTED);
        o.setReviewedById(employeeId);
        o.setReviewedAt(LocalDateTime.now());
        o.setOutcomeKind(kind);
        o.setOutcomeRef(ref);
    }

    void recordCreated(Observation o, String note) {
        events.record(InspectionEventSubject.observation(o), InspectionEventType.OBSERVATION_CREATED, null,
                InspectionEventChanges.none()
                        .field("source", null, o.getSource())
                        .field("reviewStatus", null, o.getReviewStatus())
                        .field("outcomeKind", null, o.getOutcomeKind())
                        .field("outcomeRef", null, o.getOutcomeRef())
                        .field("title", null, o.getTitle())
                        .after(),
                note);
    }

    void recordReviewed(Observation o, ObservationReviewStatus before) {
        recordReviewed(o, before, null);
    }

    private void recordReviewed(Observation o, ObservationReviewStatus before, RegionEdits regionEdits) {
        Map<String, Object> after = InspectionEventChanges.none()
                .field("reviewStatus", null, o.getReviewStatus())
                .field("rejectReason", null, o.getRejectReason())
                .field("outcomeKind", null, o.getOutcomeKind())
                .field("outcomeRef", null, o.getOutcomeRef())
                .after();
        if (regionEdits != null) {
            Map<String, Object> regions = new LinkedHashMap<>();
            regions.put("before", regionEdits.before());
            regions.put("after", regionEdits.after());
            regions.put("ops", regionEdits.ops());
            after.put("regions", regions);
        }
        events.record(InspectionEventSubject.observation(o), InspectionEventType.OBSERVATION_REVIEWED,
                Map.of("reviewStatus", InspectionEventChanges.json(before)), after, o.getReviewNote());
    }

    private static void recount(Inspection inspection) {
        int passed = 0;
        int failed = 0;
        for (InspectionCheckItem c : inspection.getCheckItems()) {
            if (c.getStatus() == CheckItemStatus.PASSED) {
                passed++;
            } else if (c.getStatus() == CheckItemStatus.FAILED) {
                failed++;
            }
        }
        inspection.setPassedCheckPoints(passed);
        inspection.setFailedCheckPoints(failed);
    }

    private static void requireSameInspection(Observation o, Inspection inspection) {
        if (o.getInspectionId() != null && !o.getInspectionId().equals(inspection.getId())) {
            throw new InvalidRequestException("The outcome belongs to a different inspection than the observation");
        }
        if (o.getProjectId() != null && inspection.getProjectId() != null
                && !o.getProjectId().equals(inspection.getProjectId())) {
            throw new InvalidRequestException("The outcome belongs to a different project than the observation");
        }
    }

    private void requireProject(Long projectId) {
        if (projectId == null || !projectRepository.existsByIdAndOrganization_Id(projectId, TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Project with ID " + projectId + " was not found");
        }
    }

    Observation require(UUID id) {
        return observationRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Observation with ID " + id + " was not found"));
    }

    private Inspection requireInspection(UUID id) {
        return inspectionRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection with ID " + id + " was not found"));
    }

    UUID resolveNode(Long projectId, UUID nodeId) {
        if (nodeId == null) {
            return null;
        }
        if (projectId == null) {
            throw new InvalidRequestException("A spatial node needs a project");
        }
        return spatialNodeService.requireUsableNode(projectId, nodeId).getId();
    }

    ObservationDto withSpatialPath(ObservationDto dto) {
        if (dto == null || dto.spatialNodeId() == null) {
            return dto;
        }
        return dto.withSpatialPath(spatialNodeService.pathOf(dto.spatialNodeId()));
    }

    Long currentEmployeeId() {
        Long userId = userContextService.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return employeeRepository.findByUserIdAndOrganizationId(userId, TenantContext.getCurrentOrgId())
                .map(Employee::getId)
                .orElse(null);
    }

    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
