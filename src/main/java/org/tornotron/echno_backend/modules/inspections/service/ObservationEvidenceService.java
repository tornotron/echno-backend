package org.tornotron.echno_backend.modules.inspections.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.modules.inspections.ObservationEvidence;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventChanges;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventSubject;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventType;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;

import java.util.List;
import java.util.UUID;

/**
 * Images and clips uploaded into Echno against an observation, through the same presigned
 * path as inspection evidence, under owner type {@value ObservationEvidence#ENTITY_TYPE}.
 * Kept apart from {@link ObservationService} so the storage client is a dependency of the
 * upload path only.
 *
 * <p>Uploads are presigned into a folder of the observation's own ({@code observation/<id>}),
 * and registration accepts only keys under that folder, so a caller who can see one
 * observation cannot file an object presigned for another against it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservationEvidenceService {

    private final ObservationRepository observationRepo;
    private final AttachmentService attachmentService;
    private final AttachmentMapper attachmentMapper;
    private final InspectionEventRecorder events;

    @Transactional(readOnly = true)
    public List<AttachmentDto> list(UUID observationId) {
        require(observationId);
        return attachmentService.getAttachments(ObservationEvidence.ENTITY_TYPE, observationId);
    }

    @Transactional(readOnly = true)
    public List<PresignedUpload> presign(UUID observationId, List<UploadRequest> uploads) {
        require(observationId);
        AttachmentOwner owner = ObservationEvidence.ownerOf(observationId);
        return attachmentService.presignUploads(uploads, owner, folderFor(observationId));
    }

    @Transactional
    public List<AttachmentDto> register(UUID observationId, List<RegisterUploadRequest> uploads) {
        Observation observation = require(observationId);
        AttachmentOwner owner = ObservationEvidence.ownerOf(observationId);
        String folder = folderFor(observationId);
        requireKeysUnder(folder, uploads);
        List<AttachmentDto> stored = attachmentService
                .registerUploads(uploads, owner, folder)
                .stream()
                .map(attachmentMapper::toDto)
                .toList();
        for (AttachmentDto attachment : stored) {
            events.record(InspectionEventSubject.observation(observation), InspectionEventType.EVIDENCE_ATTACHED,
                    null,
                    InspectionEventChanges.none()
                            .field("attachmentId", null, attachment.getId())
                            .field("fileName", null, attachment.getFileName())
                            .after(),
                    null);
        }
        log.info("Registered {} pieces of evidence against observation {}", stored.size(), observationId);
        return stored;
    }

    /** The storage folder presigned for one observation; a registered key must sit under it. */
    static String folderFor(UUID observationId) {
        return ObservationEvidence.ownerOf(observationId).folder() + "/" + observationId;
    }

    private static void requireKeysUnder(String folder, List<RegisterUploadRequest> uploads) {
        if (uploads == null) {
            return;
        }
        String prefix = folder + "/";
        for (RegisterUploadRequest upload : uploads) {
            String key = upload.key();
            if (key == null || !key.startsWith(prefix) || key.contains("/../")) {
                throw new InvalidRequestException(
                        "Storage key '" + key + "' was not presigned for this observation");
            }
        }
    }

    private Observation require(UUID id) {
        return observationRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Observation with ID " + id + " was not found"));
    }
}
