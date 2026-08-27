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
        inspection.setStatus(req.status());
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
