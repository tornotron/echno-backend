package org.tornotron.echno_backend.inspection.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.inspection.InspectionEvidence;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.domain.Inspection;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The files an inspection rests on: the permit, the certificate, the test report, the photograph
 * of the thing that was checked.
 *
 * <h2>Why evidence hangs off the inspection and not off a check point or a defect</h2>
 *
 * <p>The inspection row is the only stable identity in the aggregate. {@code
 * InspectionService.update} clears the check items and the defects and rebuilds both from the
 * payload on every save, with {@code orphanRemoval}, so every child row is deleted and reinserted
 * under a new UUID each time an inspector presses save. A file keyed on a check item id or a
 * defect id would be orphaned by the next edit, which is exactly the trap {@code
 * DefectPhotoAnnotation} had to work around by keying on the photograph rather than on the defect
 * row. The inspection's own id is never reissued, so evidence filed against it survives every
 * edit, and the test in {@code InspectionServiceIT} pins that: it records the child ids, updates,
 * and asserts they all changed while the evidence still resolves.
 *
 * <p>That is also the right domain answer for the case this exists for. A compliance inspection
 * standing in for a statutory approval carries one document that is evidence of the whole
 * inspection, not of one line on its checklist. Per-check-point and per-defect photographs already
 * have a home in the {@code photos} element collections, which a rebuild carries through by value,
 * with {@code DefectPhotoAnnotation} marking them up on top. What did not exist anywhere was a
 * place for the inspection's own paperwork, with a document type and an expiry on it.
 *
 * <h2>What happens to evidence once the inspection has a verdict</h2>
 *
 * <p>Evidence may always be added, including after the inspection has passed. The certificate
 * routinely arrives days after the work was signed off, which is the whole shape of a statutory
 * approval, and an audit trail you may not complete is not much of an audit trail.
 *
 * <p>Evidence may not be removed once the inspection has reached a verdict, that is once it is
 * passed, passed with remarks, or failed. Those files were part of the record when somebody
 * reached that conclusion, and deleting one afterwards leaves a verdict whose basis is gone with
 * nothing to say it ever existed. A file that turns out to be wrong is superseded by uploading the
 * correct one, in the same spirit as a stock adjustment corrected by a further adjustment rather
 * than by an edit. Before a verdict, an inspection is still being assembled, so removal is free.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionEvidenceService {

    /**
     * The states in which the evidence set is frozen against removal: the inspection has been
     * judged. Cancelled is not among them, because a cancelled inspection concluded nothing and
     * its files are the basis of no verdict. Completed is not either: the work is done but the
     * verdict has not been given yet.
     */
    private static final Set<InspectionStatus> VERDICT_REACHED = EnumSet.of(
            InspectionStatus.PASSED,
            InspectionStatus.PASSED_WITH_REMARKS,
            InspectionStatus.FAILED);

    private final InspectionRepository inspectionRepo;
    private final AttachmentService attachmentService;
    private final AttachmentMapper attachmentMapper;

    /**
     * The evidence filed against one inspection, oldest first, each with a short-lived download
     * URL and, where one was recorded, its document type and expiry.
     *
     * @param inspectionId The inspection to read.
     * @throws ResourceNotFoundException if the inspection is not in this tenant.
     */
    @Transactional(readOnly = true)
    public List<AttachmentDto> list(UUID inspectionId) {
        requireInspection(inspectionId);
        return attachmentService.getAttachments(InspectionEvidence.ENTITY_TYPE, inspectionId);
    }

    /**
     * Uploads evidence straight through the API. Suitable for a scanned certificate; a large site
     * photograph should go through {@link #presign} and {@link #register} instead.
     *
     * @param inspectionId The inspection the files belong to.
     * @param files        The files to store.
     * @return The stored evidence.
     * @throws ResourceNotFoundException if the inspection is not in this tenant.
     */
    @Transactional
    public List<AttachmentDto> upload(UUID inspectionId, List<MultipartFile> files) {
        Inspection inspection = requireInspection(inspectionId);
        AttachmentOwner owner = InspectionEvidence.ownerOf(inspectionId);
        List<AttachmentDto> stored = attachmentService
                .uploadAttachments(files, owner, owner.folder())
                .stream()
                .map(attachmentMapper::toDto)
                .toList();
        log.info("Filed {} pieces of evidence against inspection {}",
                stored.size(), inspection.getInspectionNumber());
        return stored;
    }

    /**
     * Step one of the direct-to-storage path: a short-lived upload URL per declared file.
     *
     * @param inspectionId The inspection the files will belong to.
     * @param uploads      The files the client intends to upload.
     * @throws ResourceNotFoundException if the inspection is not in this tenant.
     */
    @Transactional(readOnly = true)
    public List<PresignedUpload> presign(UUID inspectionId, List<UploadRequest> uploads) {
        requireInspection(inspectionId);
        AttachmentOwner owner = InspectionEvidence.ownerOf(inspectionId);
        return attachmentService.presignUploads(uploads, owner, owner.folder());
    }

    /**
     * Step two of the direct-to-storage path: records the keys the client has finished uploading,
     * after the storage layer has confirmed each object is really there.
     *
     * @param inspectionId The inspection the files belong to.
     * @param uploads      The storage keys the client says it has written.
     * @throws ResourceNotFoundException if the inspection is not in this tenant.
     */
    @Transactional
    public List<AttachmentDto> register(UUID inspectionId, List<RegisterUploadRequest> uploads) {
        Inspection inspection = requireInspection(inspectionId);
        AttachmentOwner owner = InspectionEvidence.ownerOf(inspectionId);
        List<AttachmentDto> stored = attachmentService
                .registerUploads(uploads, owner, owner.folder())
                .stream()
                .map(attachmentMapper::toDto)
                .toList();
        log.info("Registered {} pieces of evidence against inspection {}",
                stored.size(), inspection.getInspectionNumber());
        return stored;
    }

    /**
     * Removes one piece of evidence, unless the inspection has already been judged.
     *
     * @param inspectionId The inspection the file is filed against.
     * @param attachmentId The file to remove.
     * @throws ResourceNotFoundException if the inspection is not in this tenant, or the file is
     *                                   not filed against it.
     * @throws InvalidRequestException   if the inspection has reached a verdict.
     */
    @Transactional
    public void delete(UUID inspectionId, Long attachmentId) {
        Inspection inspection = requireInspection(inspectionId);
        requireNoVerdictYet(inspection);
        attachmentService.deleteAttachmentOf(InspectionEvidence.ownerOf(inspectionId), attachmentId);
        log.info("Removed evidence {} from inspection {}", attachmentId, inspection.getInspectionNumber());
    }

    /**
     * Refuses to remove evidence from an inspection that has been judged.
     *
     * @param inspection The inspection the file is filed against.
     * @throws InvalidRequestException if it is passed, passed with remarks, or failed.
     */
    private static void requireNoVerdictYet(Inspection inspection) {
        if (!VERDICT_REACHED.contains(inspection.getStatus())) {
            return;
        }
        throw new InvalidRequestException(
                "Inspection " + inspection.getInspectionNumber() + " is "
                        + inspection.getStatus().getValue() + ", so its evidence is part of the "
                        + "record of that result and cannot be removed. Upload the correct file "
                        + "instead; further evidence may still be added.");
    }

    private Inspection requireInspection(UUID inspectionId) {
        return inspectionRepo.findByIdScoped(inspectionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inspection with ID " + inspectionId + " was not found"));
    }
}
