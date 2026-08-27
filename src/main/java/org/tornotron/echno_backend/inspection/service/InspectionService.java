package org.tornotron.echno_backend.inspection.service;

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
import org.tornotron.echno_backend.inspection.CheckItemStatus;
import org.tornotron.echno_backend.inspection.DefectStatus;
import org.tornotron.echno_backend.inspection.InspectionCategory;
import org.tornotron.echno_backend.inspection.InspectionResult;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.InspectionTrade;
import org.tornotron.echno_backend.inspection.InspectionType;
import org.tornotron.echno_backend.inspection.domain.Inspection;
import org.tornotron.echno_backend.inspection.domain.InspectionCheckItem;
import org.tornotron.echno_backend.inspection.domain.InspectionDefect;
import org.tornotron.echno_backend.inspection.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionCheckItemRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapper;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.inspection.repositories.InspectionSpecifications;

import java.util.List;
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

    @Transactional(readOnly = true)
    public InspectionDto findById(UUID id) {
        return mapper.toDto(inspectionRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inspection with ID " + id + " was not found")));
    }

    @Transactional(readOnly = true)
    public Page<InspectionDto> findAll(Long projectId,
                                       InspectionStatus status,
                                       InspectionType type,
                                       InspectionCategory category,
                                       InspectionTrade trade,
                                       InspectionResult result,
                                       Pageable pageable) {
        return inspectionRepo.findAll(
                        InspectionSpecifications.withFilters(projectId, status, type, category,
                                trade, result),
                        pageable)
                .map(mapper::toDto);
    }

    @Transactional
    public InspectionDto create(CreateInspectionRequest req) {
        Inspection inspection = new Inspection();
        inspection.setInspectionNumber(numberGen.next(DOC_TYPE));
        inspection.setTitle(req.title());
        inspection.setType(req.type());
        inspection.setCategory(categoryFor(req.category(), req.type()));
        inspection.setTrade(req.trade());
        inspection.setStatus(InspectionStatus.SCHEDULED);
        inspection.setProjectId(req.projectId());
        inspection.setLocation(req.location());
        inspection.setAreaInspected(req.areaInspected());
        inspection.setDrawingReference(req.drawingReference());
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
        log.info("Created inspection {}", saved.getInspectionNumber());
        return mapper.toDto(saved);
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

        inspection.setTitle(req.title());
        inspection.setType(req.type());
        inspection.setCategory(categoryFor(req.category(), req.type()));
        inspection.setTrade(req.trade());
        transitionTo(inspection, req.status());
        inspection.setResult(req.result());
        inspection.setLocation(req.location());
        inspection.setAreaInspected(req.areaInspected());
        inspection.setDrawingReference(req.drawingReference());
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

        inspection.getCheckItems().clear();
        inspection.getDefects().clear();
        applyChildrenAndCounts(inspection, req.checkItems(), req.defects());

        Inspection saved = inspectionRepo.saveAndFlush(inspection);
        log.info("Updated inspection {}", saved.getInspectionNumber());
        return mapper.toDto(saved);
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
    private void instantiateTemplateIfEmpty(Inspection inspection) {
        if (!inspection.getCheckItems().isEmpty()) {
            return;
        }
        List<InspectionCheckItem> instantiated =
                checklistTemplateService.instantiateFor(inspection.getTrade());
        if (instantiated.isEmpty()) {
            return;
        }
        instantiated.forEach(inspection::addCheckItem);
        inspection.setTotalCheckPoints(instantiated.size());
        log.info("Instantiated {} check points from the {} checklist template",
                instantiated.size(), inspection.getTrade().getValue());
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
