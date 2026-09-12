package org.tornotron.echno_backend.modules.inspections.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
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
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public ObservationDto findById(UUID id) {
        return withSpatialPath(mapper.toDto(require(id)));
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
        return observationRepo.findAll(
                        ObservationSpecifications.withFilters(projectId, reviewStatus, source, inspectionId,
                                prefix, from, to),
                        pageable)
                .map(mapper::toDto)
                .map(this::withSpatialPath);
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
                refs.add(Map.of("attachmentId", attachmentId));
            }
            o.setEvidenceRefs(refs);
        }
        accept(o, employeeId, ObservationOutcomeKind.NONE, null);
        Observation saved = observationRepo.saveAndFlush(o);
        recordCreated(saved, null);
        log.info("Recorded human observation {} on project {}", saved.getId(), saved.getProjectId());
        return withSpatialPath(mapper.toDto(saved));
    }

    // ------------------------------------------------------ machine producers

    /** What intake returns: the row, and whether this call created it. */
    public record IntakeResult(ObservationDto observation, boolean created) {}

    /**
     * A finding from a drone, a robot, a fixed camera or a model, posted by the fleet's
     * service account. Lands pending. Idempotent on the producer's {@code externalRef}: a
     * repeat returns the row already recorded, unchanged. The check here is the fast path;
     * the partial unique index on {@code (organization_id, external_ref)} is the guarantee, and
     * a concurrent duplicate that races past the check fails on it rather than duplicating.
     */
    @Transactional
    public IntakeResult intake(IntakeObservationRequest req) {
        if (req.source() == ObservationSource.HUMAN) {
            throw new InvalidRequestException("Intake is for machine producers; people record through the web endpoint");
        }
        Optional<Observation> existing = observationRepo.findByExternalRefScoped(req.externalRef());
        if (existing.isPresent()) {
            log.debug("Observation intake repeated externalRef {}; returning the existing row", req.externalRef());
            return new IntakeResult(withSpatialPath(mapper.toDto(existing.get())), false);
        }
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
                        .after(),
                null);
        log.info("Observation {} taken in from {} {} on project {} (externalRef {})", saved.getId(),
                saved.getSource(), saved.getSourceDeviceId(), saved.getProjectId(), saved.getExternalRef());
        return new IntakeResult(withSpatialPath(mapper.toDto(saved)), true);
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
        ObservationReviewStatus decided;
        switch (req.decision()) {
            case REJECT -> {
                if (req.note() == null || req.note().isBlank()) {
                    throw new InvalidRequestException("A note is required to reject an observation");
                }
                decided = ObservationReviewStatus.REJECTED;
            }
            case ACCEPT -> {
                decided = ObservationReviewStatus.ACCEPTED;
                applyOutcome(o, req.outcome());
            }
            case MODIFY -> {
                List<Map<String, Object>> changes = diff(o, req.changes());
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
        recordReviewed(saved, ObservationReviewStatus.PENDING);
        log.info("Observation {} reviewed as {} with outcome {}", saved.getId(), decided, saved.getOutcomeKind());
        return withSpatialPath(mapper.toDto(saved));
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
        Map<UUID, Observation> byOutcome = new HashMap<>();
        for (Observation existing : observationRepo.findByInspectionScoped(saved.getId())) {
            if (existing.getOutcomeRef() != null) {
                byOutcome.put(existing.getOutcomeRef(), existing);
            }
        }
        List<InspectionCheckItem> items = saved.getCheckItems();
        for (int i = 0; i < items.size(); i++) {
            InspectionCheckItem item = items.get(i);
            Observation linked = null;
            if (i < oldItemIds.size()) {
                linked = byOutcome.get(oldItemIds.get(i));
                if (linked != null && linked.getOutcomeKind() == ObservationOutcomeKind.CHECK_ITEM) {
                    linked.setOutcomeRef(item.getId());
                } else {
                    linked = null;
                }
            }
            if (linked == null && item.getStatus() == CheckItemStatus.FAILED) {
                recordFailedCheckItem(item);
            }
        }
        List<InspectionDefect> defects = saved.getDefects();
        for (int i = 0; i < defects.size(); i++) {
            InspectionDefect defect = defects.get(i);
            if (i < oldDefectIds.size() && oldDefectObservationIds.get(i) != null) {
                defect.setObservationId(oldDefectObservationIds.get(i));
                Observation linked = byOutcome.get(oldDefectIds.get(i));
                if (linked != null && linked.getOutcomeKind() == ObservationOutcomeKind.DEFECT) {
                    linked.setOutcomeRef(defect.getId());
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
        events.record(InspectionEventSubject.observation(o), InspectionEventType.OBSERVATION_REVIEWED,
                Map.of("reviewStatus", InspectionEventChanges.json(before)),
                InspectionEventChanges.none()
                        .field("reviewStatus", null, o.getReviewStatus())
                        .field("outcomeKind", null, o.getOutcomeKind())
                        .field("outcomeRef", null, o.getOutcomeRef())
                        .after(),
                o.getReviewNote());
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
