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
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inspection.domain.DefectPhotoAnnotation;
import org.tornotron.echno_backend.inspection.domain.Inspection;
import org.tornotron.echno_backend.inspection.domain.InspectionDefect;
import org.tornotron.echno_backend.inspection.dtos.DefectPhotoAnnotationDto;
import org.tornotron.echno_backend.inspection.dtos.DefectPhotoAnnotationRequest;
import org.tornotron.echno_backend.inspection.dtos.ReplaceAnnotationsRequest;
import org.tornotron.echno_backend.inspection.mapper.DefectPhotoAnnotationMapper;
import org.tornotron.echno_backend.inspection.repositories.DefectPhotoAnnotationRepository;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The marks drawn over an inspection's defect photos: reading them back, replacing
 * them, and clearing the ones whose image is gone.
 *
 * <p>The rule that shapes all three is that an annotation belongs to a photo, not
 * to a defect row. {@link DefectPhotoAnnotation} carries the reasoning; the
 * consequences here are that a mark may only name a photo that some defect on the
 * inspection actually carries, and that {@link #removeOrphaned} runs whenever an
 * inspection's defects are saved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefectAnnotationService {

    private final DefectPhotoAnnotationRepository annotationRepo;
    private final InspectionRepository inspectionRepo;
    private final EmployeeRepository employeeRepository;
    private final UserContextService userContextService;
    private final DefectPhotoAnnotationMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;

    /**
     * The marks on one inspection, one page at a time.
     *
     * @param inspectionId The inspection to read.
     * @param pageable     Bounded by the caller; nothing here reads the whole table.
     * @throws ResourceNotFoundException if the inspection is not in this tenant.
     */
    @Transactional(readOnly = true)
    public Page<DefectPhotoAnnotationDto> findByInspection(UUID inspectionId, Pageable pageable) {
        requireInspection(inspectionId);
        return annotationRepo.findByInspection(inspectionId, pageable).map(mapper::toDto);
    }

    /**
     * Replaces every mark on an inspection with the set supplied.
     *
     * <p>A replace rather than a merge, because the client that draws these holds
     * the whole canvas: reconciling per-mark would need stable ids the drawing tool
     * does not have to give us, and would silently keep a mark the user erased.
     *
     * @param inspectionId The inspection being marked up.
     * @param req          The complete set of marks, possibly empty.
     * @return The stored marks, in print order.
     * @throws ResourceNotFoundException if the inspection is not in this tenant.
     * @throws InvalidRequestException   if a mark names a photo no defect on the
     *                                   inspection carries.
     */
    @Transactional
    public List<DefectPhotoAnnotationDto> replaceForInspection(UUID inspectionId,
                                                               ReplaceAnnotationsRequest req) {
        Inspection inspection = requireInspection(inspectionId);
        Set<String> known = photosOf(inspection);
        List<DefectPhotoAnnotationRequest> requested =
                req.annotations() == null ? List.of() : req.annotations();

        for (DefectPhotoAnnotationRequest mark : requested) {
            if (!known.contains(mark.photo())) {
                throw new InvalidRequestException(
                        "Inspection " + inspection.getInspectionNumber() + " has no defect photo "
                                + mark.photo() + ", so nothing can be annotated on it. Attach the "
                                + "photo to a defect first.");
            }
        }

        Long orgId = TenantContext.getCurrentOrgId();
        annotationRepo.deleteByInspection(orgId, inspectionId);

        if (requested.isEmpty()) {
            log.info("Cleared the photo annotations on inspection {}",
                    inspection.getInspectionNumber());
            return List.of();
        }

        Organization organization = tenantEntityHelper.resolveCurrentOrganization();
        Long author = currentEmployeeId();
        List<DefectPhotoAnnotation> rows = new ArrayList<>(requested.size());
        int order = 0;
        for (DefectPhotoAnnotationRequest mark : requested) {
            DefectPhotoAnnotation row = new DefectPhotoAnnotation();
            row.setInspectionId(inspectionId);
            row.setPhoto(mark.photo());
            row.setShape(mark.shape());
            row.setX1(mark.x1());
            row.setY1(mark.y1());
            row.setX2(mark.x2());
            row.setY2(mark.y2());
            row.setLabel(mark.label());
            row.setLineOrder(order++);
            row.setCreatedById(author);
            row.setOrganization(organization);
            rows.add(row);
        }

        List<DefectPhotoAnnotation> saved = annotationRepo.saveAll(rows);
        annotationRepo.flush();
        log.info("Stored {} photo annotations on inspection {}",
                saved.size(), inspection.getInspectionNumber());
        return saved.stream().map(mapper::toDto).toList();
    }

    /**
     * Drops the marks on an inspection whose photo is no longer attached to any of
     * its defects.
     *
     * <p>Called from the inspection save path, after the defects have been rebuilt.
     * This is the whole answer to what happens to an annotation when its image is
     * replaced: it is deleted. The geometry describes a region of specific pixels,
     * so on a different image it is not merely stale, it is wrong in a way a reader
     * cannot detect, and this record is evidence.
     *
     * @param inspection The inspection as just saved, with its current defects.
     * @return How many marks were dropped.
     */
    @Transactional
    public int removeOrphaned(Inspection inspection) {
        Long orgId = TenantContext.getCurrentOrgId();
        Set<String> kept = photosOf(inspection);

        int dropped = kept.isEmpty()
                ? annotationRepo.deleteByInspection(orgId, inspection.getId())
                : annotationRepo.deleteOrphansByInspection(orgId, inspection.getId(), kept);

        if (dropped > 0) {
            log.info("Dropped {} photo annotations on inspection {} whose photo is no longer attached",
                    dropped, inspection.getInspectionNumber());
        }
        return dropped;
    }

    /** How many marks an inspection carries, for the report's truncation note. */
    @Transactional(readOnly = true)
    public long countForInspection(UUID inspectionId) {
        return annotationRepo.countByInspection(inspectionId);
    }

    /** Every distinct photo attached to any defect on the inspection. */
    private static Set<String> photosOf(Inspection inspection) {
        Set<String> photos = new LinkedHashSet<>();
        for (InspectionDefect defect : inspection.getDefects()) {
            for (String photo : defect.getPhotos()) {
                if (photo != null && !photo.isBlank()) {
                    photos.add(photo);
                }
            }
        }
        return photos;
    }

    private Inspection requireInspection(UUID inspectionId) {
        return inspectionRepo.findByIdScoped(inspectionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inspection with ID " + inspectionId + " was not found"));
    }

    /**
     * The authenticated caller as an employee of the current tenant, or null when
     * they have no employee record, following {@code NcrService}. Null rather than a
     * refusal: an organization's bootstrap administrator has no employee profile,
     * and the mark is still worth storing without an author.
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
}
