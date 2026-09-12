package org.tornotron.echno_backend.modules.inspections.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.modules.inspections.CheckItemStatus;
import org.tornotron.echno_backend.modules.inspections.DefectStatus;
import org.tornotron.echno_backend.modules.inspections.InspectionCategory;
import org.tornotron.echno_backend.modules.inspections.InspectionResult;
import org.tornotron.echno_backend.modules.inspections.InspectionStatus;
import org.tornotron.echno_backend.modules.inspections.domain.OrgTrade;
import org.tornotron.echno_backend.modules.inspections.InspectionType;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionCheckItem;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventChanges;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventSubject;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventType;
import org.tornotron.echno_backend.modules.inspections.mapper.InspectionMapper;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;
import org.tornotron.echno_backend.project.spatial.SpatialNode;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.SpatialPathSegment;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionSpecifications;

import java.util.ArrayList;
import java.util.List;
import org.tornotron.echno_backend.modules.inspections.DefectSeverity;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * CRUD + list for site inspections. This increment does no workflow beyond the
 * status field: status and result are set directly from the request, and the
 * summary counts (total, passed, failed check points and defects found) are
 * recomputed from the supplied check items and defects on every save.
 *
 * <p>Creating an inspection for a trade the organization has an active checklist
 * template for starts it from a copy of that template's check points, so an
 * inspector opens a ready checklist instead of an empty one. See
 * {@link #applyChildrenAndCounts} for when the template is consulted.
 *
 * <p>An update rebuilds the check items and defects wholesale from the payload, so
 * every child row is deleted and reinserted under a new id. That is why the marks
 * drawn over defect photos are not children of the defect row, and why the update
 * path has to sweep the ones whose photo the payload no longer carries. See
 * {@code DefectPhotoAnnotation}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionService {

    private static final String DOC_TYPE = "INSP";

    private final InspectionRepository inspectionRepo;
    private final EntryNumberGenerator numberGen;
    private final InspectionMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final ChecklistTemplateService checklistTemplateService;
    private final TradeService tradeService;
    private final DefectAnnotationService defectAnnotationService;
    private final InspectionEventRecorder events;
    private final SpatialNodeService spatialNodeService;
    private final ObservationService observations;

    @Transactional(readOnly = true)
    public InspectionDto findById(UUID id) {
        return withSpatialPaths(mapper.toDto(inspectionRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inspection with ID " + id + " was not found"))));
    }

    @Transactional(readOnly = true)
    public Page<InspectionDto> findAll(Long projectId,
                                       InspectionStatus status,
                                       InspectionType type,
                                       InspectionCategory category,
                                       String trade,
                                       UUID tradeId,
                                       InspectionResult result,
                                       Pageable pageable) {
        return findAll(projectId, status, type, category, trade, tradeId, result, null, pageable);
    }

    /**
     * As above with the site-structure filter: {@code spatialNodeId} matches inspections on
     * that node and on every node under it. A node the caller cannot see (another tenant or
     * an unknown id) matches nothing rather than everything.
     */
    @Transactional(readOnly = true)
    public Page<InspectionDto> findAll(Long projectId,
                                       InspectionStatus status,
                                       InspectionType type,
                                       InspectionCategory category,
                                       String trade,
                                       UUID tradeId,
                                       InspectionResult result,
                                       UUID spatialNodeId,
                                       Pageable pageable) {
        String prefix = spatialNodeId == null ? null
                : spatialNodeService.subtreePathPrefix(spatialNodeId).orElse(InspectionSpecifications.NO_MATCH);
        return inspectionRepo.findAll(
                        InspectionSpecifications.withFilters(projectId, status, type, category,
                                trade, tradeId, result, prefix),
                        pageable)
                .map(mapper::toDto)
                .map(this::withSpatialPaths);
    }

    @Transactional
    public InspectionDto create(CreateInspectionRequest req) {
        Inspection inspection = new Inspection();
        inspection.setInspectionNumber(numberGen.next(DOC_TYPE));
        inspection.setTitle(req.title());
        inspection.setType(req.type());
        inspection.setCategory(categoryFor(req.category(), req.type()));
        setTrade(inspection, tradeService.resolve(req.trade(), req.tradeId()));
        inspection.setStatus(InspectionStatus.SCHEDULED);
        inspection.setProjectId(req.projectId());
        inspection.setLocation(req.location());
        inspection.setAreaInspected(req.areaInspected());
        inspection.setDrawingReference(req.drawingReference());
        inspection.setSpatialNodeId(resolveSpatialNode(req.projectId(), req.spatialNodeId(), null));
        inspection.setScheduledDate(req.scheduledDate());
        inspection.setScheduledTime(req.scheduledTime());
        inspection.setActualStartTime(req.actualStartTime());
        inspection.setActualEndTime(req.actualEndTime());
        inspection.setDuration(req.duration());
        inspection.setInspectorId(req.inspectorId());
        inspection.setContractorId(req.contractorId());
        inspection.setClientRepresentative(req.clientRepresentative());
        replaceAll(inspection.getAttendees(), req.attendees());
        inspection.setWeatherConditions(req.weatherConditions());
        inspection.setTemperature(req.temperature());
        inspection.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        applyChildrenAndCounts(inspection, req.checkItems(), req.defects());
        instantiateTemplateIfEmpty(inspection);

        Inspection saved = inspectionRepo.saveAndFlush(inspection);
        recordCreation(saved);
        observations.recordImplicit(saved);
        log.info("Created inspection {}", saved.getInspectionNumber());
        return withSpatialPaths(mapper.toDto(saved));
    }

    /**
     * Replaces an inspection, except for the project it is against. The project is
     * chosen when the inspection is created and is fixed from then on: a statutory
     * approval has to keep a permanent, traceable relationship with the project it
     * was obtained for, and a compliance inspection is additionally identified by
     * its project (the duplicate check is keyed on project plus rule code), so
     * moving one would let the same compliance be generated twice for the original
     * project.
     *
     * @param id  Id of the inspection to replace.
     * @param req The replacement payload. Its project id may repeat the stored one
     *            or be omitted, but it may not name a different project.
     * @throws ResourceNotFoundException if no such inspection exists in this tenant.
     * @throws InvalidRequestException   if the payload names a different project.
     */
    @Transactional
    public InspectionDto update(UUID id, UpdateInspectionRequest req) {
        Inspection inspection = inspectionRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inspection with ID " + id + " was not found"));

        requireSameProject(inspection, req.projectId());
        InspectionSnapshot before = InspectionSnapshot.of(inspection);

        inspection.setTitle(req.title());
        inspection.setType(req.type());
        inspection.setCategory(categoryFor(req.category(), req.type()));
        setTrade(inspection, tradeService.resolve(req.trade(), req.tradeId()));
        transitionTo(inspection, req.status());
        inspection.setResult(req.result());
        inspection.setLocation(req.location());
        inspection.setAreaInspected(req.areaInspected());
        inspection.setDrawingReference(req.drawingReference());
        inspection.setSpatialNodeId(resolveSpatialNode(inspection.getProjectId(), req.spatialNodeId(),
                inspection.getSpatialNodeId()));
        inspection.setScheduledDate(req.scheduledDate());
        inspection.setScheduledTime(req.scheduledTime());
        inspection.setActualStartTime(req.actualStartTime());
        inspection.setActualEndTime(req.actualEndTime());
        inspection.setDuration(req.duration());
        inspection.setInspectorId(req.inspectorId());
        inspection.setContractorId(req.contractorId());
        inspection.setClientRepresentative(req.clientRepresentative());
        replaceAll(inspection.getAttendees(), req.attendees());
        inspection.setWeatherConditions(req.weatherConditions());
        inspection.setTemperature(req.temperature());

        List<UUID> oldItemIds = inspection.getCheckItems().stream().map(InspectionCheckItem::getId).toList();
        List<UUID> oldDefectIds = inspection.getDefects().stream().map(InspectionDefect::getId).toList();
        List<UUID> oldDefectObservationIds = new ArrayList<>();
        inspection.getDefects().forEach(d -> oldDefectObservationIds.add(d.getObservationId()));

        inspection.getCheckItems().clear();
        inspection.getDefects().clear();
        applyChildrenAndCounts(inspection, req.checkItems(), req.defects());

        Inspection saved = inspectionRepo.saveAndFlush(inspection);
        defectAnnotationService.removeOrphaned(saved);
        recordUpdate(before, saved);
        observations.carryOver(oldItemIds, oldDefectIds, oldDefectObservationIds, saved);
        log.info("Updated inspection {}", saved.getInspectionNumber());
        return withSpatialPaths(mapper.toDto(saved));
    }

    /**
     * Moves an inspection along its lifecycle, or refuses the move.
     *
     * <p>This is the only place the status is written after creation. It used to be
     * taken from the payload as given, so an inspection could go from cancelled back
     * to passed, or from passed to scheduled, and the record would then show a
     * conclusion that was never reached. The graph in
     * {@link InspectionStatus#canTransitionTo} is deliberately permissive about how
     * an inspection is concluded, because work is often carried out and recorded
     * afterwards; what it refuses is coming back out of a conclusion.
     *
     * <p>A payload that repeats the stored status passes, which is the normal case:
     * the web client sends the whole record back on every save.
     *
     * @param inspection The inspection being updated.
     * @param target     The status the request asks for.
     * @throws InvalidRequestException if the move is not part of the lifecycle.
     */
    private void recordCreation(Inspection saved) {
        InspectionEventSubject subject = InspectionEventSubject.inspection(saved);
        events.record(subject, InspectionEventType.INSPECTION_CREATED, null,
                InspectionEventChanges.none()
                        .field("inspectionNumber", null, saved.getInspectionNumber())
                        .field("title", null, saved.getTitle())
                        .field("type", null, saved.getType())
                        .field("category", null, saved.getCategory())
                        .field("trade", null, tradeCode(saved))
                        .field("status", null, saved.getStatus())
                        .field("projectId", null, saved.getProjectId())
                        .field("inspectorId", null, saved.getInspectorId())
                        .field("scheduledDate", null, saved.getScheduledDate())
                        .after(),
                null);
        for (InspectionCheckItem item : saved.getCheckItems()) {
            if (item.getStatus() != CheckItemStatus.PENDING) {
                events.record(InspectionEventSubject.checkItem(item),
                        InspectionEventType.CHECK_ITEM_RESULT_RECORDED, null,
                        checkItemResult(item).after(), item.getCheckPoint());
            }
        }
        for (InspectionDefect defect : saved.getDefects()) {
            events.record(InspectionEventSubject.defect(defect), InspectionEventType.DEFECT_CREATED,
                    null, defectFields(null, defect).after(), defect.getDescription());
        }
    }

    /**
     * Turns the difference between the inspection as loaded and as saved into events: one for
     * the status when it moved (cancellation named as such), one for the result, one for the
     * rest of the header, and one per check point or defect that changed.
     *
     * <p>Check points and defects are replaced wholesale on update, so they are paired by
     * position: the row that was at line {@code n} before against the one at line {@code n}
     * after. The event's subject is the new row, which is the one a reader can open.
     */
    private void recordUpdate(InspectionSnapshot before, Inspection saved) {
        InspectionEventSubject subject = InspectionEventSubject.inspection(saved);
        if (before.status() != saved.getStatus()) {
            String type = saved.getStatus() == InspectionStatus.CANCELLED
                    ? InspectionEventType.INSPECTION_CANCELLED
                    : InspectionEventType.INSPECTION_STATUS_CHANGED;
            events.record(subject, type,
                    Map.of("status", InspectionEventChanges.json(before.status())),
                    Map.of("status", InspectionEventChanges.json(saved.getStatus())), null);
        }
        if (before.result() != saved.getResult()) {
            InspectionEventChanges result = InspectionEventChanges.none()
                    .field("result", before.result(), saved.getResult());
            events.record(subject, InspectionEventType.INSPECTION_RESULT_RECORDED,
                    result.before(), result.after(), null);
        }
        InspectionEventChanges header = InspectionEventChanges.none()
                .field("title", before.title(), saved.getTitle())
                .field("type", before.type(), saved.getType())
                .field("category", before.category(), saved.getCategory())
                .field("trade", before.trade(), tradeCode(saved))
                .field("location", before.location(), saved.getLocation())
                .field("areaInspected", before.areaInspected(), saved.getAreaInspected())
                .field("drawingReference", before.drawingReference(), saved.getDrawingReference())
                .field("scheduledDate", before.scheduledDate(), saved.getScheduledDate())
                .field("scheduledTime", before.scheduledTime(), saved.getScheduledTime())
                .field("actualStartTime", before.actualStartTime(), saved.getActualStartTime())
                .field("actualEndTime", before.actualEndTime(), saved.getActualEndTime())
                .field("duration", before.duration(), saved.getDuration())
                .field("inspectorId", before.inspectorId(), saved.getInspectorId())
                .field("contractorId", before.contractorId(), saved.getContractorId())
                .field("clientRepresentative", before.clientRepresentative(), saved.getClientRepresentative())
                .field("attendees", String.join(", ", before.attendees()), String.join(", ", saved.getAttendees()))
                .field("weatherConditions", before.weatherConditions(), saved.getWeatherConditions())
                .field("temperature", before.temperature(), saved.getTemperature());
        if (!header.isEmpty()) {
            events.record(subject, InspectionEventType.INSPECTION_UPDATED,
                    header.before(), header.after(), null);
        }
        observations.reviewSuggestedInspection(saved, before.status(), header);

        List<InspectionCheckItem> items = saved.getCheckItems();
        for (int i = 0; i < items.size(); i++) {
            InspectionCheckItem item = items.get(i);
            CheckItemSnapshot old = i < before.checkItems().size() ? before.checkItems().get(i) : null;
            if (old == null) {
                if (item.getStatus() != CheckItemStatus.PENDING) {
                    events.record(InspectionEventSubject.checkItem(item),
                            InspectionEventType.CHECK_ITEM_RESULT_RECORDED, null,
                            checkItemResult(item).after(), item.getCheckPoint());
                }
                continue;
            }
            InspectionEventChanges result = InspectionEventChanges.none()
                    .field("status", old.status(), item.getStatus())
                    .field("measurement", old.measurement(), item.getMeasurement())
                    .field("deviation", old.deviation(), item.getDeviation());
            if (!result.isEmpty()) {
                events.record(InspectionEventSubject.checkItem(item),
                        InspectionEventType.CHECK_ITEM_RESULT_RECORDED,
                        result.before(), result.after(), item.getCheckPoint());
            }
            InspectionEventChanges remarks = InspectionEventChanges.none()
                    .field("remarks", old.remarks(), item.getRemarks());
            if (!remarks.isEmpty()) {
                events.record(InspectionEventSubject.checkItem(item),
                        InspectionEventType.CHECK_ITEM_REMARKS_RECORDED,
                        remarks.before(), remarks.after(), item.getCheckPoint());
            }
        }

        List<InspectionDefect> defects = saved.getDefects();
        for (int i = 0; i < defects.size(); i++) {
            InspectionDefect defect = defects.get(i);
            DefectSnapshot old = i < before.defects().size() ? before.defects().get(i) : null;
            if (old == null) {
                events.record(InspectionEventSubject.defect(defect), InspectionEventType.DEFECT_CREATED,
                        null, defectFields(null, defect).after(), defect.getDescription());
                continue;
            }
            if (old.status() != defect.getStatus()) {
                InspectionEventChanges status = InspectionEventChanges.none()
                        .field("status", old.status(), defect.getStatus())
                        .field("resolvedDate", old.resolvedDate(), defect.getResolvedDate());
                events.record(InspectionEventSubject.defect(defect),
                        InspectionEventType.DEFECT_STATUS_CHANGED,
                        status.before(), status.after(), defect.getDescription());
            }
            InspectionEventChanges fields = defectFields(old, defect);
            if (!fields.isEmpty()) {
                events.record(InspectionEventSubject.defect(defect), InspectionEventType.DEFECT_UPDATED,
                        fields.before(), fields.after(), defect.getDescription());
            }
        }
    }

    private static InspectionEventChanges checkItemResult(InspectionCheckItem item) {
        return InspectionEventChanges.none()
                .field("status", null, item.getStatus())
                .field("measurement", null, item.getMeasurement())
                .field("deviation", null, item.getDeviation());
    }

    /** The defect's descriptive fields; with {@code old} null, every one is a creation value. */
    private static InspectionEventChanges defectFields(DefectSnapshot old, InspectionDefect defect) {
        return InspectionEventChanges.none()
                .field("category", old == null ? null : old.category(), defect.getCategory())
                .field("description", old == null ? null : old.description(), defect.getDescription())
                .field("severity", old == null ? null : old.severity(), defect.getSeverity())
                .field("location", old == null ? null : old.location(), defect.getLocation())
                .field("correctiveAction", old == null ? null : old.correctiveAction(), defect.getCorrectiveAction())
                .field("responsibleParty", old == null ? null : old.responsibleParty(), defect.getResponsibleParty())
                .field("targetDate", old == null ? null : old.targetDate(), defect.getTargetDate())
                .field("status", old == null ? null : old.status(), old == null ? defect.getStatus() : old.status());
    }

    /** The inspection as loaded, held apart from the entity the update then rewrites in place. */
    private record InspectionSnapshot(String title, InspectionType type, InspectionCategory category,
                                      String trade, InspectionStatus status, InspectionResult result,
                                      String location, String areaInspected, String drawingReference,
                                      LocalDate scheduledDate, String scheduledTime,
                                      LocalDateTime actualStartTime, LocalDateTime actualEndTime,
                                      Integer duration, Long inspectorId, Long contractorId,
                                      String clientRepresentative, List<String> attendees,
                                      String weatherConditions, String temperature,
                                      List<CheckItemSnapshot> checkItems, List<DefectSnapshot> defects) {
        static InspectionSnapshot of(Inspection i) {
            return new InspectionSnapshot(i.getTitle(), i.getType(), i.getCategory(), tradeCode(i),
                    i.getStatus(), i.getResult(), i.getLocation(), i.getAreaInspected(),
                    i.getDrawingReference(), i.getScheduledDate(), i.getScheduledTime(),
                    i.getActualStartTime(), i.getActualEndTime(), i.getDuration(),
                    i.getInspectorId(), i.getContractorId(), i.getClientRepresentative(),
                    List.copyOf(i.getAttendees()), i.getWeatherConditions(), i.getTemperature(),
                    i.getCheckItems().stream().map(CheckItemSnapshot::of).toList(),
                    i.getDefects().stream().map(DefectSnapshot::of).toList());
        }
    }

    private record CheckItemSnapshot(CheckItemStatus status, String remarks, String measurement,
                                     BigDecimal deviation) {
        static CheckItemSnapshot of(InspectionCheckItem c) {
            return new CheckItemSnapshot(c.getStatus(), c.getRemarks(), c.getMeasurement(), c.getDeviation());
        }
    }

    private record DefectSnapshot(String category, String description, DefectSeverity severity,
                                  String location, String correctiveAction, String responsibleParty,
                                  LocalDate targetDate, DefectStatus status, LocalDate resolvedDate) {
        static DefectSnapshot of(InspectionDefect d) {
            return new DefectSnapshot(d.getCategory(), d.getDescription(), d.getSeverity(), d.getLocation(),
                    d.getCorrectiveAction(), d.getResponsibleParty(), d.getTargetDate(), d.getStatus(),
                    d.getResolvedDate());
        }
    }

    private static void transitionTo(Inspection inspection, InspectionStatus target) {
        InspectionStatus current = inspection.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new InvalidRequestException(
                    "Inspection " + inspection.getInspectionNumber() + " is " + current.getValue()
                            + " and cannot move to " + target.getValue() + ". From "
                            + current.getValue() + " it may move to " + allowedFrom(current) + ".");
        }
        inspection.setStatus(target);
    }

    private static String allowedFrom(InspectionStatus current) {
        if (current.allowedNext().isEmpty()) {
            return "nothing: this is where the inspection ends";
        }
        return String.join(", ",
                current.allowedNext().stream().map(InspectionStatus::getValue).toList());
    }

    /**
     * Rejects an update that would move an inspection to another project. A payload
     * that omits the project, or repeats the one already stored, passes: the web
     * client sends the whole record back on every save, so the stored id arriving
     * unchanged is the normal case rather than an attempt to reassign. Only a
     * genuinely different id is refused.
     *
     * @param inspection The inspection being replaced.
     * @param projectId  The project id carried by the request, possibly null.
     * @throws InvalidRequestException if the two disagree.
     */
    private void requireSameProject(Inspection inspection, Long projectId) {
        if (projectId == null || projectId.equals(inspection.getProjectId())) {
            return;
        }
        throw new InvalidRequestException(
                "Inspection " + inspection.getInspectionNumber() + " belongs to project "
                        + inspection.getProjectId() + " and cannot be moved to project "
                        + projectId + ". The project is fixed when the inspection is created.");
    }

    /**
     * Rebuilds the check items and defects from the request and derives the four
     * summary counts: total check points is the number of check items, passed and
     * failed are counted from their statuses, and defects found is the number of
     * defects.
     */
    private void applyChildrenAndCounts(Inspection inspection,
                                        List<InspectionCheckItemRequest> checkItemReqs,
                                        List<InspectionDefectRequest> defectReqs) {
        int passed = 0;
        int failed = 0;

        if (checkItemReqs != null) {
            for (InspectionCheckItemRequest cr : checkItemReqs) {
                InspectionCheckItem item = new InspectionCheckItem();
                item.setCategory(cr.category());
                item.setCheckPoint(cr.checkPoint());
                item.setSpecification(cr.specification());
                item.setStatus(cr.status());
                item.setRemarks(cr.remarks());
                item.setPhotosRequired(cr.photosRequired());
                replaceAll(item.getPhotos(), cr.photos());
                item.setMeasurement(cr.measurement());
                item.setExpectedValue(cr.expectedValue());
                item.setAcceptanceCriterion(cr.acceptanceCriterion());
                item.setTolerance(cr.tolerance());
                item.setDeviation(MeasurementDeviation.of(cr.measurement(), cr.expectedValue()));
                item.setBimElementGuid(cr.bimElementGuid());
                item.setSpatialNodeId(resolveChildSpatialNode(inspection, cr.spatialNodeId(), "check point"));
                item.setPriority(cr.priority() != null ? cr.priority() : "medium");
                inspection.addCheckItem(item);

                if (cr.status() == CheckItemStatus.PASSED) {
                    passed++;
                } else if (cr.status() == CheckItemStatus.FAILED) {
                    failed++;
                }
            }
        }

        int defectCount = 0;
        if (defectReqs != null) {
            for (InspectionDefectRequest dr : defectReqs) {
                InspectionDefect defect = new InspectionDefect();
                defect.setCategory(dr.category());
                defect.setDescription(dr.description());
                defect.setSeverity(dr.severity());
                defect.setLocation(dr.location());
                defect.setSpatialNodeId(resolveChildSpatialNode(inspection, dr.spatialNodeId(), "defect"));
                replaceAll(defect.getPhotos(), dr.photos());
                defect.setCorrectiveAction(dr.correctiveAction());
                defect.setResponsibleParty(dr.responsibleParty());
                defect.setTargetDate(dr.targetDate());
                defect.setStatus(dr.status() != null ? dr.status() : DefectStatus.OPEN);
                defect.setResolvedDate(dr.resolvedDate());
                inspection.addDefect(defect);
                defectCount++;
            }
        }

        inspection.setTotalCheckPoints(checkItemReqs != null ? checkItemReqs.size() : 0);
        inspection.setPassedCheckPoints(passed);
        inspection.setFailedCheckPoints(failed);
        inspection.setDefectsFound(defectCount);
    }

    /**
     * The site-structure node an inspection may point at: any level, in the inspection's
     * project, not archived. A reference the inspection already holds is kept as it is even
     * if the node was archived since, so an old inspection stays editable; only a new or
     * changed reference is checked. Null clears the reference and leaves the free text as
     * the only location.
     */
    private UUID resolveSpatialNode(Long projectId, UUID requested, UUID current) {
        if (requested == null || requested.equals(current)) {
            return requested;
        }
        if (projectId == null) {
            throw new InvalidRequestException("A spatial node needs the inspection to be against a project");
        }
        return spatialNodeService.requireUsableNode(projectId, requested).getId();
    }

    /**
     * A defect or check point should sit on a zone or an element. A coarser node is accepted
     * with a warning rather than refused, so a site with a building-only tree is not blocked.
     */
    private UUID resolveChildSpatialNode(Inspection inspection, UUID requested, String what) {
        if (requested == null) {
            return null;
        }
        if (inspection.getProjectId() == null) {
            throw new InvalidRequestException("A spatial node on a " + what + " needs the inspection to be against a project");
        }
        SpatialNode node = spatialNodeService.requireUsableNode(inspection.getProjectId(), requested);
        if (node.getLevel().depth() < SpatialLevel.ZONE.depth()) {
            log.warn("Inspection {} {} placed on {} {} rather than a zone or element",
                    inspection.getInspectionNumber(), what, node.getLevel(), node.getCode());
        }
        return node.getId();
    }

    /** Fills the breadcrumbs on an inspection and its children from one batch lookup. */
    private InspectionDto withSpatialPaths(InspectionDto dto) {
        if (dto == null) {
            return null;
        }
        List<InspectionCheckItemDto> checkItems = dto.checkItems() == null ? List.of() : dto.checkItems();
        List<InspectionDefectDto> defectDtos = dto.defects() == null ? List.of() : dto.defects();
        List<UUID> ids = new ArrayList<>();
        ids.add(dto.spatialNodeId());
        checkItems.forEach(c -> ids.add(c.spatialNodeId()));
        defectDtos.forEach(d -> ids.add(d.spatialNodeId()));
        Map<UUID, List<SpatialPathSegment>> paths = spatialNodeService.pathsOf(ids);
        List<InspectionCheckItemDto> items = checkItems.stream()
                .map(c -> c.withSpatialPath(pathFor(paths, c.spatialNodeId())))
                .toList();
        List<InspectionDefectDto> defects = defectDtos.stream()
                .map(d -> d.withSpatialPath(pathFor(paths, d.spatialNodeId())))
                .toList();
        return dto.withChildren(items, defects).withSpatialPath(pathFor(paths, dto.spatialNodeId()));
    }

    private static List<SpatialPathSegment> pathFor(Map<UUID, List<SpatialPathSegment>> paths, UUID nodeId) {
        if (nodeId == null) {
            return List.of();
        }
        return paths.getOrDefault(nodeId, List.of());
    }

    /**
     * The category to store: the one the caller stated, or the one derived from the
     * inspection type when the request leaves it out. Keeping the fallback here means
     * a client that has not yet been updated for the taxonomy still produces rows in
     * the right bucket.
     */
    private static InspectionCategory categoryFor(InspectionCategory requested, InspectionType type) {
        return requested != null ? requested : InspectionCategory.defaultFor(type);
    }

    /**
     * Starts a new inspection from the organization's checklist for its trade, when
     * the caller supplied no check points of their own.
     *
     * <p>An explicit list always wins. A client that sends its own check points has
     * said what this inspection covers, and a template is a default, not an override:
     * silently appending template rows to a hand-built checklist would double up every
     * check point on the re-inspection of a failed item. The template is therefore
     * consulted only for an inspection that would otherwise start empty, which is the
     * normal case for one scheduled against a trade.
     *
     * <p>The counts are recomputed here rather than left to
     * {@link #applyChildrenAndCounts}, because the instantiated items are only known
     * after it has run. Instantiated items are all {@code PENDING}, so only the total
     * moves; passed and failed stay at zero until the inspection is carried out.
     */
    /** The trade slug as the wire and the event log carry it: the org row's code, else the enum's value. */
    @SuppressWarnings("deprecation")
    private static String tradeCode(Inspection inspection) {
        if (inspection.getTradeRef() != null) {
            return inspection.getTradeRef().getCode();
        }
        return inspection.getTrade() == null ? null : inspection.getTrade().getValue();
    }

    /** Sets the org trade row and keeps the legacy enum column in step for the shim. */
    @SuppressWarnings("deprecation")
    private static void setTrade(Inspection inspection, OrgTrade trade) {
        inspection.setTradeRef(trade);
        inspection.setTrade(trade == null ? null : trade.legacyTrade());
    }

    private void instantiateTemplateIfEmpty(Inspection inspection) {
        if (!inspection.getCheckItems().isEmpty()) {
            return;
        }
        List<InspectionCheckItem> instantiated =
                checklistTemplateService.instantiateFor(inspection.getTradeRef());
        if (instantiated.isEmpty()) {
            return;
        }
        instantiated.forEach(inspection::addCheckItem);
        inspection.setTotalCheckPoints(instantiated.size());
        log.info("Instantiated {} check points from the {} checklist template",
                instantiated.size(), inspection.getTradeRef().getCode());
    }

    /**
     * Replaces the contents of a managed collection in place rather than swapping
     * the reference, which keeps Hibernate's element-collection tracking intact.
     */
    private static void replaceAll(List<String> target, List<String> source) {
        target.clear();
        if (source != null) {
            target.addAll(source);
        }
    }
}
