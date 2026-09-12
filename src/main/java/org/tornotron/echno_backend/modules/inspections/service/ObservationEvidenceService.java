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
        return attachmentService.presignUploads(uploads, owner, owner.folder());
    }

    @Transactional
    public List<AttachmentDto> register(UUID observationId, List<RegisterUploadRequest> uploads) {
        Observation observation = require(observationId);
        AttachmentOwner owner = ObservationEvidence.ownerOf(observationId);
        List<AttachmentDto> stored = attachmentService
                .registerUploads(uploads, owner, owner.folder())
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

    private Observation require(UUID id) {
        return observationRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Observation with ID " + id + " was not found"));
    }
}
